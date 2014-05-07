package com.thinkaurelius.titan.diskstorage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.thinkaurelius.titan.util.time.StandardDuration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.CacheTransaction;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.KCVEntryMutation;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.KCVSCache;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.NoKCVSCache;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayBuffer;

import static com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore.*;

import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntry;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

public abstract class MultiWriteKeyColumnValueStoreTest extends AbstractKCVSTest {

    private Logger log = LoggerFactory.getLogger(MultiWriteKeyColumnValueStoreTest.class);

    int numKeys = 500;
    int numColumns = 50;

    int bufferSize = 20;

    protected String storeName1 = "testStore1";
    private KCVSCache store1;
    protected String storeName2 = "testStore2";
    private KCVSCache store2;


    public KeyColumnValueStoreManager manager;
    public StoreTransaction tx;


    private Random rand = new Random(10);

    @Before
    public void setUp() throws Exception {
        openStorageManager().clearStorage();
        open();
    }

    @After
    public void tearDown() throws Exception {
        close();
    }

    public abstract KeyColumnValueStoreManager openStorageManager() throws StorageException;

    public void open() throws StorageException {
        manager = openStorageManager();
        tx = new CacheTransaction(manager.beginTransaction(getTxConfig()), manager, bufferSize, new StandardDuration(100, TimeUnit.MILLISECONDS), true);
        store1 = new NoKCVSCache(manager.openDatabase(storeName1));
        store2 = new NoKCVSCache(manager.openDatabase(storeName2));

    }

    public void close() throws StorageException {
        if (tx != null) tx.commit();
        if (null != store1) store1.close();
        if (null != store2) store2.close();
        if (null != manager) manager.close();
    }

    public void clopen() throws StorageException {
        close();
        open();
    }

    public void newTx() throws StorageException {
        if (tx!=null) tx.commit();
        tx = new CacheTransaction(manager.beginTransaction(getTxConfig()), manager, bufferSize, new StandardDuration(100, TimeUnit.MILLISECONDS), true);
    }

    @Test
    public void deletionsAppliedBeforeAdditions() throws StorageException {

        StaticBuffer b1 = KeyColumnValueStoreUtil.longToByteBuffer(1);

        Assert.assertNull(KCVSUtil.get(store1, b1, b1, tx));

        List<Entry> additions = Lists.newArrayList(StaticArrayEntry.of(b1, b1));

        List<Entry> deletions = Lists.newArrayList(additions);

        Map<StaticBuffer, KCVEntryMutation> combination = new HashMap<StaticBuffer, KCVEntryMutation>(1);
        Map<StaticBuffer, KCVEntryMutation> deleteOnly = new HashMap<StaticBuffer, KCVEntryMutation>(1);
        Map<StaticBuffer, KCVEntryMutation> addOnly = new HashMap<StaticBuffer, KCVEntryMutation>(1);

        combination.put(b1, new KCVEntryMutation(additions, deletions));
        deleteOnly.put(b1, new KCVEntryMutation(KeyColumnValueStore.NO_ADDITIONS, deletions));
        addOnly.put(b1, new KCVEntryMutation(additions, KCVSCache.NO_DELETIONS));

        store1.mutateEntries(b1, additions, deletions, tx);
        newTx();

        StaticBuffer result = KCVSUtil.get(store1, b1, b1, tx);

        Assert.assertEquals(b1, result);

        store1.mutateEntries(b1, NO_ADDITIONS, deletions, tx);
        newTx();

        for (int i = 0; i < 100; i++) {
            StaticBuffer n = KCVSUtil.get(store1, b1, b1, tx);
            Assert.assertNull(n);
            store1.mutateEntries(b1, additions, KCVSCache.NO_DELETIONS, tx);
            newTx();
            store1.mutateEntries(b1, NO_ADDITIONS, deletions, tx);
            newTx();
            n = KCVSUtil.get(store1, b1, b1, tx);
            Assert.assertNull(n);
        }

        for (int i = 0; i < 100; i++) {
            store1.mutateEntries(b1, NO_ADDITIONS, deletions, tx);
            newTx();
            store1.mutateEntries(b1, additions, KCVSCache.NO_DELETIONS, tx);
            newTx();
            Assert.assertEquals(b1, KCVSUtil.get(store1, b1, b1, tx));
        }

        for (int i = 0; i < 100; i++) {
            store1.mutateEntries(b1, additions, deletions, tx);
            newTx();
            Assert.assertEquals(b1, KCVSUtil.get(store1, b1, b1, tx));
        }
    }

    @Test
    public void mutateManyWritesSameKeyOnMultipleCFs() throws StorageException {

        final long arbitraryLong = 42;
        assert 0 < arbitraryLong;

        final StaticBuffer key = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong * arbitraryLong);
        final StaticBuffer val = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong * arbitraryLong * arbitraryLong);
        final StaticBuffer col = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong);
        final StaticBuffer nextCol = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong + 1);

        final StoreTransaction directTx = manager.beginTransaction(getTxConfig());

        KCVMutation km = new KCVMutation(
                Lists.newArrayList(StaticArrayEntry.of(col, val)),
                Lists.<StaticBuffer>newArrayList());

        Map<StaticBuffer, KCVMutation> keyColumnAndValue = ImmutableMap.of(key, km);

        Map<String, Map<StaticBuffer, KCVMutation>> mutations =
                ImmutableMap.of(
                        storeName1, keyColumnAndValue,
                        storeName2, keyColumnAndValue);

        manager.mutateMany(mutations, directTx);

        directTx.commit();

        KeySliceQuery query = new KeySliceQuery(key, col, nextCol);
        List<Entry> expected =
                ImmutableList.<Entry>of(StaticArrayEntry.of(col, val));

        Assert.assertEquals(expected, store1.getSlice(query, tx));
        Assert.assertEquals(expected, store2.getSlice(query, tx));

    }

    @Test
    public void mutateManyStressTest() throws StorageException {

        Map<StaticBuffer, Map<StaticBuffer, StaticBuffer>> state =
                new HashMap<StaticBuffer, Map<StaticBuffer, StaticBuffer>>();

        int dels = 1024;
        int adds = 4096;

        for (int round = 0; round < 5; round++) {
            Map<StaticBuffer, KCVEntryMutation> changes = mutateState(state, dels, adds);

            applyChanges(changes, store1, tx);
            applyChanges(changes, store2, tx);
            newTx();

            int deletesExpected = 0 == round ? 0 : dels;

            int stateSizeExpected = adds + (adds - dels) * round;

            Assert.assertEquals(stateSizeExpected, checkThatStateExistsInStore(state, store1, round));
            Assert.assertEquals(deletesExpected, checkThatDeletionsApplied(changes, store1, round));

            Assert.assertEquals(stateSizeExpected, checkThatStateExistsInStore(state, store2, round));
            Assert.assertEquals(deletesExpected, checkThatDeletionsApplied(changes, store2, round));
        }
    }

    public void applyChanges(Map<StaticBuffer, KCVEntryMutation> changes, KCVSCache store, StoreTransaction tx) throws StorageException {
        for (Map.Entry<StaticBuffer, KCVEntryMutation> change : changes.entrySet()) {
            store.mutateEntries(change.getKey(), change.getValue().getAdditions(), change.getValue().getDeletions(), tx);
        }
    }

    public int checkThatStateExistsInStore(Map<StaticBuffer, Map<StaticBuffer, StaticBuffer>> state, KeyColumnValueStore store, int round) throws StorageException {
        int checked = 0;

        for (StaticBuffer key : state.keySet()) {
            for (StaticBuffer col : state.get(key).keySet()) {
                StaticBuffer val = state.get(key).get(col);

                Assert.assertEquals(val, KCVSUtil.get(store, key, col, tx));

                checked++;
            }
        }

        log.debug("Checked existence of {} key-column-value triples on round {}", checked, round);

        return checked;
    }

    public int checkThatDeletionsApplied(Map<StaticBuffer, KCVEntryMutation> changes, KeyColumnValueStore store, int round) throws StorageException {
        int checked = 0;
        int skipped = 0;

        for (StaticBuffer key : changes.keySet()) {
            KCVEntryMutation m = changes.get(key);

            if (!m.hasDeletions())
                continue;

            List<Entry> deletions = m.getDeletions();

            List<Entry> additions = m.getAdditions();

            for (Entry entry : deletions) {
                StaticBuffer col = entry.getColumn();

                if (null != additions && additions.contains(StaticArrayEntry.of(col, col))) {
                    skipped++;
                    continue;
                }

                Assert.assertNull(KCVSUtil.get(store, key, col, tx));

                checked++;
            }
        }

        log.debug("Checked absence of {} key-column-value deletions on round {} (skipped {})", new Object[]{checked, round, skipped});

        return checked;
    }

    /**
     * Pseudorandomly change the supplied {@code state}.
     * <p/>
     * This method removes {@code min(maxDeletionCount, S)} entries from the
     * maps in {@code state.values()}, where {@code S} is the sum of the sizes
     * of the maps in {@code state.values()}; this method then adds
     * {@code additionCount} pseudorandomly generated entries spread across
     * {@code state.values()}, potentially adding new keys to {@code state}
     * since they are randomly generated. This method then returns a map of keys
     * to Mutations representing the changes it has made to {@code state}.
     *
     * @param state            Maps keys -> columns -> values
     * @param maxDeletionCount Remove at most this many entries from state
     * @param additionCount    Add exactly this many entries to state
     * @return A KCVMutation map
     */
    public Map<StaticBuffer, KCVEntryMutation> mutateState(
            Map<StaticBuffer, Map<StaticBuffer, StaticBuffer>> state,
            int maxDeletionCount, int additionCount) {

        final int keyLength = 8;
        final int colLength = 16;

        Map<StaticBuffer, KCVEntryMutation> result = new HashMap<StaticBuffer, KCVEntryMutation>();

        // deletion pass
        int dels = 0;

        StaticBuffer key = null, col = null;
        Entry entry = null;

        Iterator<StaticBuffer> keyIter = state.keySet().iterator();

        while (keyIter.hasNext() && dels < maxDeletionCount) {
            key = keyIter.next();

            Iterator<Map.Entry<StaticBuffer,StaticBuffer>> colIter =
                    state.get(key).entrySet().iterator();

            while (colIter.hasNext() && dels < maxDeletionCount) {
                Map.Entry<StaticBuffer,StaticBuffer> colEntry = colIter.next();
                entry = StaticArrayEntry.of(colEntry.getKey(),colEntry.getValue());

                if (!result.containsKey(key)) {
                    KCVEntryMutation m = new KCVEntryMutation(new LinkedList<Entry>(),
                            new LinkedList<Entry>());
                    result.put(key, m);
                }

                result.get(key).deletion(entry);

                dels++;

                colIter.remove();

                if (state.get(key).isEmpty()) {
                    assert !colIter.hasNext();
                    keyIter.remove();
                }
            }
        }

        // addition pass
        for (int i = 0; i < additionCount; i++) {

            while (true) {
                byte keyBuf[] = new byte[keyLength];
                rand.nextBytes(keyBuf);
                key = new StaticArrayBuffer(keyBuf);

                byte colBuf[] = new byte[colLength];
                rand.nextBytes(colBuf);
                col = new StaticArrayBuffer(colBuf);

                if (!state.containsKey(key) || !state.get(key).containsKey(col)) {
                    break;
                }
            }

            if (!state.containsKey(key)) {
                Map<StaticBuffer, StaticBuffer> m = new HashMap<StaticBuffer, StaticBuffer>();
                state.put(key, m);
            }

            state.get(key).put(col, col);

            if (!result.containsKey(key)) {
                KCVEntryMutation m = new KCVEntryMutation(new LinkedList<Entry>(),
                        new LinkedList<Entry>());
                result.put(key, m);
            }

            result.get(key).addition(StaticArrayEntry.of(col, col));

        }

        return result;
    }

    public Map<StaticBuffer, KCVMutation> generateMutation(int keyCount, int columnCount, Map<StaticBuffer, KCVMutation> deleteFrom) {
        Map<StaticBuffer, KCVMutation> result = new HashMap<StaticBuffer, KCVMutation>(keyCount);

        Random keyRand = new Random(keyCount);
        Random colRand = new Random(columnCount);

        final int keyLength = 8;
        final int colLength = 6;

        Iterator<Map.Entry<StaticBuffer, KCVMutation>> deleteIter = null;
        List<Entry> lastDeleteIterResult = null;

        if (null != deleteFrom) {
            deleteIter = deleteFrom.entrySet().iterator();
        }

        for (int ik = 0; ik < keyCount; ik++) {
            byte keyBuf[] = new byte[keyLength];
            keyRand.nextBytes(keyBuf);
            StaticBuffer key = new StaticArrayBuffer(keyBuf);

            List<Entry> additions = new LinkedList<Entry>();
            List<StaticBuffer> deletions = new LinkedList<StaticBuffer>();

            for (int ic = 0; ic < columnCount; ic++) {

                boolean deleteSucceeded = false;
                if (null != deleteIter && 1 == ic % 2) {

                    if (null == lastDeleteIterResult || lastDeleteIterResult.isEmpty()) {
                        while (deleteIter.hasNext()) {
                            Map.Entry<StaticBuffer, KCVMutation> ent = deleteIter.next();
                            if (ent.getValue().hasAdditions() && !ent.getValue().getAdditions().isEmpty()) {
                                lastDeleteIterResult = ent.getValue().getAdditions();
                                break;
                            }
                        }
                    }


                    if (null != lastDeleteIterResult && !lastDeleteIterResult.isEmpty()) {
                        Entry e = lastDeleteIterResult.get(0);
                        lastDeleteIterResult.remove(0);
                        deletions.add(e.getColumn());
                        deleteSucceeded = true;
                    }
                }

                if (!deleteSucceeded) {
                    byte colBuf[] = new byte[colLength];
                    colRand.nextBytes(colBuf);
                    StaticBuffer col = new StaticArrayBuffer(colBuf);

                    additions.add(StaticArrayEntry.of(col, col));
                }

            }

            KCVMutation m = new KCVMutation(additions, deletions);

            result.put(key, m);
        }

        return result;
    }
}
