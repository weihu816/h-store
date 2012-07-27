package edu.brown.hstore;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;

import org.voltdb.catalog.ConflictSet;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;

import edu.brown.BaseTestCase;
import edu.brown.benchmark.tm1.procedures.UpdateLocation;
import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.internal.InternalMessage;
import edu.brown.hstore.internal.StartTxnMessage;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.ProjectType;

public class TestSpecExecScheduler extends BaseTestCase {
    
    private static final int NUM_PARTITIONS = 5;
    private static final int BASE_PARTITION = 1;
    private static long NEXT_TXN_ID = 1;
    
    private MockHStoreSite hstore_site;
    private final Queue<InternalMessage> work_queue = new LinkedList<InternalMessage>();
    private SpecExecScheduler scheduler;
    private LocalTransaction dtxn;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp(ProjectType.TM1);
        this.initializeCluster(2, 2, NUM_PARTITIONS);
        
        Site catalog_site = this.getSite(0);
        this.hstore_site = new MockHStoreSite(catalog_site, HStoreConf.singleton());
        
        this.scheduler = new SpecExecScheduler(BASE_PARTITION, this.work_queue, catalogContext);
        
        // Create our current distributed transaction
        Procedure catalog_proc = this.getProcedure(UpdateLocation.class);
        this.dtxn = new LocalTransaction(this.hstore_site);
        this.dtxn.testInit(NEXT_TXN_ID++,
                           BASE_PARTITION,
                           catalogContext.getAllPartitionIdCollection(),
                           catalog_proc);
    }
    
    /**
     * testNonConflicting
     */
    public void testNonConflicting() throws Exception {
        // Make a single-partition txn for a procedure that has no conflicts with
        // our dtxn and add it to our queue. It should always be returned 
        // and marked as speculative by the scheduler
        Collection<Procedure> conflicts = CatalogUtil.getAllConflicts(dtxn.getProcedure());
        Procedure proc = null;
        for (Procedure p : catalogContext.getRegularProcedures()) {
            if (conflicts.contains(p) == false) {
                proc = p;
                break;
            }
        } // FOR
        assertNotNull(proc);
        
        LocalTransaction ts = new LocalTransaction(this.hstore_site);
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        
        StartTxnMessage next = this.scheduler.next(this.dtxn);
        assertNotNull(next);
        assertEquals(ts, next.getTransaction());
        assertTrue(ts.isSpeculative());
        assertFalse(this.work_queue.contains(next));
    }
    
    /**
     * testWriteWriteConflicting
     */
    public void testWriteWriteConflicting() throws Exception {
        // Make a single-partition txn for a procedure that has a write-write conflict with
        // our dtxn and add it to our queue. It should only be allowed to be returned by next()
        // if the current dtxn has not written to that table yet (but reads are allowed)
        Procedure dtxnProc = dtxn.getProcedure();
        Procedure proc = null;
        for (Procedure p : catalogContext.getRegularProcedures()) {
            Collection<Procedure> c = CatalogUtil.getWriteWriteConflicts(p);
            if (c.contains(dtxnProc)) {
                proc = p;
                break;
            }
        } // FOR
        assertNotNull(proc);
        
        ConflictSet cs = proc.getConflicts().get(dtxnProc.getName());
        assertNotNull(cs);
        Collection<Table> conflictTables = CatalogUtil.getTablesFromRefs(cs.getWritewriteconflicts());
        assertFalse(conflictTables.isEmpty());
        
        // First time we should be able to get through
        LocalTransaction ts = new LocalTransaction(this.hstore_site);
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        StartTxnMessage next = this.scheduler.next(this.dtxn);
        assertNotNull(next);
        assertEquals(ts, next.getTransaction());
        assertTrue(ts.isSpeculative());
        assertFalse(this.work_queue.contains(next));
        ts.finish();
        
        // Now have the dtxn "write" to one of the tables in our ConflictSet
        dtxn.clearReadWriteSets();
        dtxn.markTableAsWritten(BASE_PARTITION, CollectionUtil.first(conflictTables));
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        next = this.scheduler.next(this.dtxn);
        assertNull(next);
        assertFalse(ts.isSpeculative());
        ts.finish();
        
        // Reads aren't allowed either
        dtxn.clearReadWriteSets();
        dtxn.markTableAsRead(BASE_PARTITION, CollectionUtil.first(conflictTables));
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        next = this.scheduler.next(this.dtxn);
        assertNull(next);
        assertFalse(ts.isSpeculative());
        ts.finish();
    }
    
    /**
     * testReadWriteConflicting
     */
    public void testReadWriteConflicting() throws Exception {
        // Make a single-partition txn for a procedure that has a read-write conflict with
        // our dtxn and add it to our queue. We will first test it without updating the
        // dtxn's read/write table set, which means that our single-partition txn should be
        // returned by next(). We will then mark the conflict table as written by the dtxn,
        // which means that the single-partition txn should *not* be returned
        Procedure dtxnProc = dtxn.getProcedure();
        Procedure proc = null;
        for (Procedure p : catalogContext.getRegularProcedures()) {
            Collection<Procedure> c = CatalogUtil.getReadWriteConflicts(p);
            if (c.contains(dtxnProc)) {
                proc = p;
                break;
            }
        } // FOR
        assertNotNull(proc);
        
        ConflictSet cs = proc.getConflicts().get(dtxnProc.getName());
        assertNotNull(cs);
        Collection<Table> conflictTables = CatalogUtil.getTablesFromRefs(cs.getReadwriteconflicts());
        assertFalse(conflictTables.isEmpty());
        
        LocalTransaction ts = new LocalTransaction(this.hstore_site);
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        StartTxnMessage next = this.scheduler.next(this.dtxn);
        assertNotNull(next);
        assertEquals(ts, next.getTransaction());
        assertTrue(ts.isSpeculative());
        assertFalse(this.work_queue.contains(next));
        ts.finish();
        
        // Reads are allowed!
        dtxn.clearReadWriteSets();
        dtxn.markTableAsRead(BASE_PARTITION, CollectionUtil.first(conflictTables));
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        next = this.scheduler.next(this.dtxn);
        assertNotNull(next);
        assertEquals(ts, next.getTransaction());
        assertTrue(ts.isSpeculative());
        assertFalse(this.work_queue.contains(next));
        ts.finish();
        
        // But writes are not!
        dtxn.clearReadWriteSets();
        dtxn.markTableAsWritten(BASE_PARTITION, CollectionUtil.first(conflictTables));
        ts.testInit(NEXT_TXN_ID++, BASE_PARTITION, new PartitionSet(BASE_PARTITION), proc);
        assertTrue(ts.isPredictSinglePartition());
        this.work_queue.add(new StartTxnMessage(ts));
        next = this.scheduler.next(this.dtxn);
        assertNull(next);
        assertFalse(ts.isSpeculative());
    }

}