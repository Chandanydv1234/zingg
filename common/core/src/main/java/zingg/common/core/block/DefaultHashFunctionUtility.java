package zingg.common.core.block;

import zingg.common.client.FieldDefinition;
import zingg.common.core.hash.HashFunction;

public class DefaultHashFunctionUtility<D, R, C, T> implements IHashFunctionUtility<D, R, C, T> {

    private long isUsedCalls = 0;
    private long totalIsUsedNano = 0;
    private int isUsedDepth = 0;

    @Override
    public boolean isHashFunctionUsed(FieldDefinition fieldDefinition, HashFunction<D, R, C, T> hashFunction, Tree<Canopy<R>> tree, Canopy<R> node) {
        long start = System.nanoTime();
        isUsedDepth++;
        isUsedCalls++;
        try {
            boolean isUsed = false;
            if (node == null || tree == null) {
                return false;
            }
            if (checkFunctionInNode(node, fieldDefinition.fieldName, hashFunction)) {
                return true;
            }
            Tree<Canopy<R>> nodeTree = tree.getTree(node);
            if (nodeTree == null) {
                return false;
            }

            Tree<Canopy<R>> parent = nodeTree.getParent();
            if (parent != null) {
                Canopy<R> head = parent.getHead();
                while (head != null) {
                    return isHashFunctionUsed(fieldDefinition, hashFunction, tree, head);
                }
            }
            return isUsed;
        } finally {
            isUsedDepth--;
            if (isUsedDepth == 0) {
                totalIsUsedNano += (System.nanoTime() - start);
            }
        }
    }

    @Override
    public void addHashFunctionIfRequired(Canopy<R> node) {
        // no-op in default mode
    }

    @Override
    public void removeHashFunctionIfRequired(Canopy<R> node) {
        // no-op in default mode
    }

    private boolean checkFunctionInNode(Canopy<R> node, String name, HashFunction<D, R, C, T> function) {
        return node.getFunction() != null && node.getFunction().equals(function)
                && node.context.fieldName.equals(name);
    }

    @Override
    public void logTotalTime() {
        double isUsedMs = totalIsUsedNano / 1_000_000.0;
        System.out.println("[DEFAULT] isHashFunctionUsed : calls=" + isUsedCalls + "  time=" + isUsedMs + " ms");
        System.out.println("[DEFAULT] add                : calls=0  time=0.0 ms  (no-op)");
        System.out.println("[DEFAULT] remove             : calls=0  time=0.0 ms  (no-op)");
        System.out.println("[DEFAULT] TOTAL              :                          time=" + isUsedMs + " ms");
    }
}
