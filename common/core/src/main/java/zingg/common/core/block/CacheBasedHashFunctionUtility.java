package zingg.common.core.block;

import zingg.common.client.FieldDefinition;
import zingg.common.core.hash.HashFunction;

import java.util.HashSet;
import java.util.Set;

public class CacheBasedHashFunctionUtility<D, R, C, T> implements IHashFunctionUtility<D, R, C, T> {

    private final Set<String> hashFunctionsInCurrentNodePath;
    private static final String DELIMITER = ":";

    private long isUsedCalls = 0;
    private long totalIsUsedNano = 0;
    private long addCalls = 0;
    private long totalAddNano = 0;
    private long removeCalls = 0;
    private long totalRemoveNano = 0;
    private long getKeyCalls = 0;
    private long totalGetKeyNano = 0;

    public CacheBasedHashFunctionUtility() {
        this.hashFunctionsInCurrentNodePath = new HashSet<String>();
    }

    @Override
    public boolean isHashFunctionUsed(FieldDefinition fieldDefinition, HashFunction<D, R, C, T> hashFunction, Tree<Canopy<R>> tree, Canopy<R> node) {
        long start = System.nanoTime();
        isUsedCalls++;
        try {
            return hashFunctionsInCurrentNodePath.contains(getKey(fieldDefinition, hashFunction));
        } finally {
            totalIsUsedNano += (System.nanoTime() - start);
        }
    }

    @Override
    public void addHashFunctionIfRequired(Canopy<R> node) {
        long start = System.nanoTime();
        addCalls++;
        try {
            hashFunctionsInCurrentNodePath.add(getKey(node.getContext(), node.getFunction()));
        } finally {
            totalAddNano += (System.nanoTime() - start);
        }
    }

    @Override
    public void removeHashFunctionIfRequired(Canopy<R> node) {
        long start = System.nanoTime();
        removeCalls++;
        try {
            hashFunctionsInCurrentNodePath.remove(getKey(node.getContext(), node.getFunction()));
        } finally {
            totalRemoveNano += (System.nanoTime() - start);
        }
    }

    private String getKey(FieldDefinition fieldDefinition, HashFunction<D, R, C, T> hashFunction) {
        long start = System.nanoTime();
        getKeyCalls++;
        try {
            return fieldDefinition.getName() + DELIMITER + hashFunction.getName();
        } finally {
            totalGetKeyNano += (System.nanoTime() - start);
        }
    }

    @Override
    public void logTotalTime() {
        double isUsedMs = totalIsUsedNano  / 1_000_000.0;
        double addMs    = totalAddNano     / 1_000_000.0;
        double removeMs = totalRemoveNano  / 1_000_000.0;
        double getKeyMs = totalGetKeyNano  / 1_000_000.0;
        double totalMs  = (totalIsUsedNano + totalAddNano + totalRemoveNano) / 1_000_000.0;
        System.out.println("[CACHED] isHashFunctionUsed : calls=" + isUsedCalls + "  time=" + isUsedMs  + " ms");
        System.out.println("[CACHED] add                : calls=" + addCalls    + "  time=" + addMs     + " ms");
        System.out.println("[CACHED] remove             : calls=" + removeCalls + "  time=" + removeMs  + " ms");
        System.out.println("[CACHED] getKey (within ^)  : calls=" + getKeyCalls + "  time=" + getKeyMs  + " ms");
        System.out.println("[CACHED] TOTAL              :                          time=" + totalMs    + " ms");
    }
}
