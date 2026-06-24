package zingg.common.core.block;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import zingg.common.client.FieldDefinition;
import zingg.common.client.ZFrame;
import zingg.common.client.ZinggClientException;
import zingg.common.client.util.ListMap;
import zingg.common.core.feature.FeatureFactory;
import zingg.common.core.hash.HashFunction;

public abstract class Block<D, R, C, T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Log LOG = LogFactory.getLog(Block.class);
    private final IHashFunctionUtility<D, R, C, T> hashFunctionUtility;
    private FieldDefinitionStrategy<R> fieldDefinitionStrategy;

    // timing counters
    private long getBestNodeCalls = 0;
    private long totalGetBestNodeNano = 0;
    private long getBlockingTreeCalls = 0;
    private long totalGetBlockingTreeNano = 0;
    private int getBlockingTreeDepth = 0;
    private long estimateElimCountCalls = 0;
    private long totalEstimateElimCountNano = 0;
    private long estimateCanopiesCalls = 0;
    private long totalEstimateCanopiesNano = 0;
    private long getCanopiesCalls = 0;
    private long totalGetCanopiesNano = 0;
    private long totalAddLeafNano = 0;
    private long totalClearBeforeSavingNano = 0;
    private long totalCopyToNano = 0;
    private long totalGetTrainingSizeNano = 0;

    protected ZFrame<D, R, C> dupes;
    ListMap<T, HashFunction<D, R, C, T>> functionsMap;
    long maxSize;
    ZFrame<D, R, C> training;
    protected ListMap<HashFunction<D, R, C, T>, String> childless;

    public Block() {
        this.hashFunctionUtility = HashFunctionUtilityFactory.getHashFunctionUtility(HashUtility.CACHED);
    }

    public Block(ZFrame<D, R, C> training, ZFrame<D, R, C> dupes) {
        this();
        this.training = training;
        this.dupes = dupes;
        childless = new ListMap<HashFunction<D, R, C, T>, String>();
    }

    public Block(ZFrame<D, R, C> training, ZFrame<D, R, C> dupes,
            ListMap<T, HashFunction<D, R, C, T>> functionsMap, long maxSize, FieldDefinitionStrategy<R> fieldDefinitionStrategy) {
        this(training, dupes);
        this.functionsMap = functionsMap;
        this.maxSize = maxSize;
        this.fieldDefinitionStrategy = fieldDefinitionStrategy;
    }

    public ZFrame<D, R, C> getDupes() {
        return dupes;
    }

    public void setDupes(ZFrame<D, R, C> dupes) {
        this.dupes = dupes;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    public Map<T, List<HashFunction<D, R, C, T>>> getFunctionsMap() {
        return functionsMap;
    }

    protected void setFunctionsMap(ListMap<T, HashFunction<D, R, C, T>> m) {
        this.functionsMap = m;
    }

    protected Canopy<R> getCanopy() {
        return new Canopy<R>();
    }

    public Canopy<R> getNodeFromCurrent(Canopy<R> node, HashFunction<D, R, C, T> function, FieldDefinition context) {
        Canopy<R> trial = getCanopy();
        trial = node.copyTo(trial);
        trial.function = function;
        trial.context = context;
        return trial;
    }

    public void estimateElimCount(Canopy<R> c, long elimCount) {
        long start = System.nanoTime();
        estimateElimCountCalls++;
        try {
            c.estimateElimCount();
        } finally {
            totalEstimateElimCountNano += (System.nanoTime() - start);
        }
    }

    public Canopy<R> getBestNode(Tree<Canopy<R>> tree, Canopy<R> parent, Canopy<R> node,
            List<FieldDefinition> fieldsOfInterest) throws Exception {
        long start = System.nanoTime();
        getBestNodeCalls++;
        try {
            long least = Long.MAX_VALUE;
            Canopy<R> best = null;
            List<FieldDefinition> adjustedFieldOfInterestList = getFieldOfInterestList(fieldsOfInterest, node);
            for (FieldDefinition field : adjustedFieldOfInterestList) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Trying for " + field + " with data type " + field.getDataType() + " and real dt "
                            + getFeatureFactory().getDataTypeFromString(field.getDataType()));
                }
                FieldDefinition context = field;
                if (least == 0) {
                    break;
                }
                List<HashFunction<D, R, C, T>> functions = functionsMap.get(getFeatureFactory().getDataTypeFromString(field.getDataType()));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("functions are " + functions);
                }
                if (functions != null) {
                    for (HashFunction function : functions) {
                        if (least == 0) {
                            break;
                        }
                        if (!hashFunctionUtility.isHashFunctionUsed(field, function, tree, node)) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Evaluating field " + field.fieldName + " and function " + function + " for " + field.dataType);
                            }
                            Canopy<R> trial = getNodeFromCurrent(node, function, context);
                            estimateElimCount(trial, least);
                            long elimCount = trial.getElimCount();
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Elim Count is " + elimCount + " ,least is " + least + ", dupe count " + node.dupeN.size());
                            }
                            if (least > elimCount) {
                                long startEst = System.nanoTime();
                                estimateCanopiesCalls++;
                                long childrenSize = trial.estimateCanopies();
                                totalEstimateCanopiesNano += (System.nanoTime() - startEst);
                                if (childrenSize > 1) {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("Yes, this fn has potential " + function);
                                    }
                                    least = elimCount;
                                    best = trial;
                                    best.elimCount = least;
                                } else {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug("No child " + function);
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LOG.debug("functions are null??????");
                }
            }
            return best;
        } finally {
            totalGetBestNodeNano += (System.nanoTime() - start);
        }
    }

	public Tree<Canopy<R>> getBlockingTree(Tree<Canopy<R>> tree, Canopy<R> parent,
			Canopy<R> node, List<FieldDefinition> fieldsOfInterest) throws Exception, ZinggClientException {
		long start = System.nanoTime();
		boolean isRootCall = (getBlockingTreeDepth == 0);
		if (isRootCall) {
			Canopy.resetStats();
			totalAddLeafNano = 0;
			totalClearBeforeSavingNano = 0;
			totalCopyToNano = 0;
			totalGetTrainingSizeNano = 0;
		}
		getBlockingTreeDepth++;
		getBlockingTreeCalls++;
		try {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Tree so far ");
				LOG.debug(tree);
			}
			long startSize = System.nanoTime();
			long size = node.getTrainingSize();
			totalGetTrainingSizeNano += (System.nanoTime() - startSize);
			if (LOG.isDebugEnabled()) {
				LOG.debug("Size, maxSize " + size + ", " + maxSize);
			}
			if (size > maxSize && node.getDupeN() != null && node.getDupeN().size() > 0) {
				LOG.debug("Size is bigger ");
				Canopy<R> best = getBestNode(tree, parent, node, fieldsOfInterest);
				if (best != null) {
					hashFunctionUtility.addHashFunctionIfRequired(best);
					if (LOG.isDebugEnabled()) {
						LOG.debug(" HashFunction is " + best + " and node is " + node);
					}
					long startCopy = System.nanoTime();
					best.copyTo(node);
					totalCopyToNano += (System.nanoTime() - startCopy);
					if (tree == null && parent == null) {
						tree = new Tree<Canopy<R>>(node);
					}
					long startCan = System.nanoTime();
					getCanopiesCalls++;
					List<Canopy<R>> canopies = node.getCanopies();
					totalGetCanopiesNano += (System.nanoTime() - startCan);
					if (LOG.isDebugEnabled()) {
						LOG.debug(" Children size is " + canopies.size());
					}
					for (Canopy<R> n : canopies) {
						long startClear = System.nanoTime();
						node.clearBeforeSaving();
						totalClearBeforeSavingNano += (System.nanoTime() - startClear);
						
						long startAddLeaf = System.nanoTime();
						tree.addLeaf(node, n);
						totalAddLeafNano += (System.nanoTime() - startAddLeaf);
						if (LOG.isDebugEnabled()) {
							LOG.debug(" Finding for " + n);
						}
						getBlockingTree(tree, node, n, fieldsOfInterest);
					}
					hashFunctionUtility.removeHashFunctionIfRequired(best);
				} else {
					long startClear = System.nanoTime();
					node.clearBeforeSaving();
					totalClearBeforeSavingNano += (System.nanoTime() - startClear);
				}
			} else {
				if ((node.getDupeN() == null) || (node.getDupeN().size() == 0)) {
					LOG.warn("Ran out of training at size " + size + " for node " + node);
				} else {
					LOG.debug("Min size reached " + size + " for node " + node);
					if (tree == null) {
						throw new ZinggClientException("Unable to create Zingg models due to insufficient data. Please run Zingg after adding more data");
					}
				}
				long startClear = System.nanoTime();
				node.clearBeforeSaving();
				totalClearBeforeSavingNano += (System.nanoTime() - startClear);
			}
			return tree;
		} finally {
			getBlockingTreeDepth--;
			totalGetBlockingTreeNano += (System.nanoTime() - start);
			if (isRootCall) {
				printBlockSummary();
				hashFunctionUtility.logTotalTime();
			}
		}
	}

	private void printBlockSummary() {
		System.out.println("[BLOCK] getBlockingTree  : calls=" + getBlockingTreeCalls + "  time=" + ms(totalGetBlockingTreeNano) + " ms");
		System.out.println("[BLOCK] getBestNode      : calls=" + getBestNodeCalls + "  time=" + ms(totalGetBestNodeNano) + " ms");
		System.out.println("[BLOCK] estimateElimCount: calls=" + estimateElimCountCalls + "  time=" + ms(totalEstimateElimCountNano) + " ms");
		System.out.println("[BLOCK] estimateCanopies : calls=" + estimateCanopiesCalls + "  time=" + ms(totalEstimateCanopiesNano) + " ms");
		System.out.println("[BLOCK] getCanopies      : calls=" + getCanopiesCalls + "  time=" + ms(totalGetCanopiesNano) + " ms");
		System.out.println("[BLOCK] addLeaf          :                          time=" + ms(totalAddLeafNano) + " ms");
		System.out.println("[BLOCK] clearBeforeSaving:                          time=" + ms(totalClearBeforeSavingNano) + " ms");
		System.out.println("[BLOCK] copyTo           :                          time=" + ms(totalCopyToNano) + " ms");
		System.out.println("[BLOCK] getTrainingSize  :                          time=" + ms(totalGetTrainingSizeNano) + " ms");
		Canopy.printStats();
	}

    private static double ms(long nano) {
        return nano / 1_000_000.0;
    }

    public List<Canopy<R>> getHashSuccessors(Collection<Canopy<R>> successors, Object hash) {
        List<Canopy<R>> retCanopy = new ArrayList<Canopy<R>>();
        for (Canopy<R> c : successors) {
            if (hash == null && c != null && c.getHash() == null) {
                retCanopy.add(c);
            }
            if (c != null && c.getHash() != null && c.getHash().equals(hash)) {
                retCanopy.add(c);
            }
        }
        return retCanopy;
    }

    public static <R> StringBuilder applyTree(R tuple, Tree<Canopy<R>> tree,
            Canopy<R> root, StringBuilder result) {
        if (root.function != null) {
            Object hash = root.function.apply(tuple, root.context.fieldName);
            result = result.append("|").append(hash);
            for (Canopy<R> c : tree.getSuccessors(root)) {
                if (c != null) {
                    if ((c.getHash() != null)) {
                        if ((c.getHash().equals(hash))) {
                            applyTree(tuple, tree, c, result);
                        }
                    }
                }
            }
        }
        return result;
    }

    public void printTree(Tree<Canopy<R>> tree, Canopy<R> root) {
        if (root.dupeN != null) {
            LOG.info(" dupeN not null " + root);
            LOG.info(root.dupeN.size());
        }
        if (root.dupeRemaining != null) {
            LOG.info(" dupeRemaining not null " + root);
            LOG.info(root.dupeRemaining.size());
        }
        if (root.training != null) {
            LOG.info(" training not null " + root);
            LOG.info(root.training.size());
        }
        for (Canopy<R> c : tree.getSuccessors(root)) {
            printTree(tree, c);
        }
    }

    public List<FieldDefinition> getFieldOfInterestList(List<FieldDefinition> fieldDefinitions, Canopy<R> node) {
        return fieldDefinitionStrategy.getAdjustedFieldDefinitions(fieldDefinitions, node);
    }

    public abstract FeatureFactory<T> getFeatureFactory();

    public void setFieldDefinitionStrategy(FieldDefinitionStrategy<R> fieldDefinitionStrategy) {
        this.fieldDefinitionStrategy = fieldDefinitionStrategy;
    }
}
