package hex.gbm;

import hex.gbm.DTree.*;
import hex.rng.MersenneTwisterRNG;
import java.util.Arrays;
import java.util.Random;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.*;
import water.util.Log.Tag.Sys;
import water.util.Log;

// Random Forest Trees
public class DRF extends Job {
  public static final String KEY_PREFIX = "__DRFModel_";

  long _cm[/*actual*/][/*predicted*/]; // Confusion matrix
  public long[][] cm() { return _cm; }

  public static final Key makeKey() { return Key.make(KEY_PREFIX + Key.make());  }
  private DRF(Key dest, Frame fr) { super("DRF "+fr, dest); }
  // Called from a non-FJ thread; makea a DRF and hands it over to FJ threads
  public static DRF start(Key dest, final Frame fr, final int maxDepth, final int ntrees, final int mtrys, final double sampleRate, final long seed) {
    final DRF job = new DRF(dest, fr);
    H2O.submitTask(job.start(new H2OCountedCompleter() {
        @Override public void compute2() { job.run(fr,maxDepth,ntrees,mtrys,(float)sampleRate,seed); tryComplete(); }
      })); 
    return job;
  }

  // ==========================================================================

  // Compute a DRF tree.  

  // Start by splitting all the data according to some criteria (minimize
  // variance at the leaves).  Record on each row which split it goes to, and
  // assign a split number to it (for next pass).  On *this* pass, use the
  // split-number to build a per-split histogram, with a per-histogram-bucket
  // variance.

  // Compute a single DRF tree from the Frame.  Last column is the response
  // variable.  Depth is capped at maxDepth.
  private void run(Frame fr, int maxDepth, int ntrees, int mtrys, float sampleRate, long seed ) {
    Timer t_drf = new Timer();
    assert 0 <= ntrees && ntrees < 1000000;
    assert 0 <= mtrys && mtrys < fr.numCols();
    assert 0.0 < sampleRate && sampleRate <= 1.0;

    final String names[] = fr._names;
    Vec vs[] = fr._vecs;
    final int ncols = vs.length-1; // Last column is the response column

    // Response column is the last one in the frame
    Vec vresponse = vs[ncols];
    final long nrows = vresponse.length();
    assert !vresponse._isInt || (vresponse.max() - vresponse.min()) < 10000; // Too many classes?
    int ymin = (int)vresponse.min();
    short nclass = vresponse._isInt ? (short)(vresponse.max()-ymin+1) : 1;

    // Find the initial prediction - the current average response variable.
    if( nclass == 1 ) {
      fr.add("Residual-"+fr._names[ncols],vresponse.makeCon(vresponse.mean()));
      throw H2O.unimpl();
    } else {
      long cs[] = new ClassDist(nclass).doAll(vresponse)._cs;
      String[] domain = vresponse.domain();
      for( int i=0; i<nclass; i++ ) 
        fr.add("Residual-"+domain[i],vresponse.makeCon(1.0f-(float)cs[i]/nrows));
    }

    // The RNG used to pick split columns
    Random rand = new MersenneTwisterRNG(new int[]{(int)(seed>>32L),(int)seed});

    // Initially setup as-if an empty-split had just happened
    DHistogram hs[] = DBinHistogram.initialHist(fr,ncols,nclass);
    DRFTree trees[] = new DRFTree[ntrees];
    Vec[] nids = new Vec[ntrees];

    // ----
    // Only work on so many trees at once, else get GC issues.
    // Hand the inner loop a smaller set of trees.
    final int NTREE=1;          // Limit of 5 trees at once
    int depth=0;
    for( int st = 0; st < ntrees; st+= NTREE ) {
      int xtrees = Math.min(NTREE,ntrees-st);
      DRFTree someTrees[] = new DRFTree[xtrees];
      int someLeafs[] = new int[xtrees];

      for( int t=0; t<xtrees; t++ ) {
        int idx = st+t;
        // Make a new Vec to hold the split-number for each row (initially all zero).
        Vec vec = vs[0].makeZero();
        nids[idx] = vec;
        trees[idx] = someTrees[t] = new DRFTree(fr,ncols,nclass,hs,mtrys,rand.nextLong());
        if( sampleRate < 1.0 )
          new Sample(someTrees[t],sampleRate).doAll(vec);
        fr.add("NIDs"+t,vec);
      }

      // Make NTREE trees at once
      int d = makeSomeTrees(st, someTrees,someLeafs, xtrees, maxDepth, fr, ncols, nclass, ymin, nrows, sampleRate);
      if( d>depth ) depth=d;    // Actual max depth used

      // Remove temp vectors; cleanup the Frame
      for( int t=0; t<xtrees; t++ )
        UKV.remove(fr.remove(fr.numCols()-1)._key);
    }
    Log.info(Sys.DRF__,"DRF done in "+t_drf);

    // One more pass for final prediction error
    _cm = new BulkScore(trees,ncols,nclass,ymin,sampleRate).doAll(fr).report( Sys.DRF__, nrows, depth )._cm;
  }

  // ----
  // One Big Loop till the tree is of proper depth.
  // Adds a layer to the tree each pass.
  public int makeSomeTrees( int st, DRFTree trees[], int leafs[], int ntrees, int maxDepth, Frame fr, int ncols, short nclass, int ymin, long nrows, double sampleRate ) {
    for( int depth=0; depth<maxDepth; depth++ ) {
      Timer t_pass = new Timer();

      // Fuse 2 conceptual passes into one:
      // Pass 1: Score a prior DHistogram, and make new DTree.Node assignments
      // to every row.  This involves pulling out the current assigned Node,
      // "scoring" the row against that Node's decision criteria, and assigning
      // the row to a new child Node (and giving it an improved prediction).
      // Pass 2: Build new summary DHistograms on the new child Nodes every row
      // got assigned into.  Collect counts, mean, variance, min, max per bin,
      // per column.
      ScoreBuildHistogram sbh = new ScoreBuildHistogram(trees,leafs,ncols,nclass,ymin,fr).doAll(fr);

      // Reassign the new DHistograms back into the DTrees
      for( int t=0; t<ntrees; t++ ) {
        final int tmax = trees[t]._len; // Number of total splits
        final DTree tree = trees[t];
        long sum=0;
        for( int i=leafs[t]; i<tmax; i++ ) {
          tree.undecided(i)._hs = sbh.getFinalHisto(t,i);
          DHistogram hs[] = tree.undecided(i)._hs;
          for( DHistogram h : hs )
            if( h != null ) sum += h.byteSize();
        }
        //System.out.println("Tree#"+(st+t)+", leaves="+(trees[t]._len-leafs[t])+", histo size="+PrettyPrint.bytes(sum)+", time="+t_pass);
      }

      // Build up the next-generation tree splits from the current histograms.
      // Nearly all leaves will split one more level.  This loop nest is
      //           O( #active_splits * #bins * #ncols )
      // but is NOT over all the data.
      boolean still_splitting=false;
      for( int t=0; t<ntrees; t++ ) {
        final DTree tree = trees[t];
        final int tmax = tree._len; // Number of total splits
        int leaf = leafs[t];
        for( ; leaf<tmax; leaf++ ) {
          //System.out.println("Tree#"+(st+t)+", "+tree.undecided(leaf));
          // Replace the Undecided with the Split decision
          new DRFDecidedNode((DRFUndecidedNode)tree.undecided(leaf));
        }
        leafs[t] = leaf;
        // If we did not make any new splits, then the tree is split-to-death
        if( tmax < tree._len ) still_splitting = true;
      }

      // If all trees are done, then so are we
      if( !still_splitting ) return depth;
      //new BulkScore(trees,ncols,nclass,ymin,(float)sampleRate).doAll(fr).report( Sys.DRF__, nrows, depth );
    }
    return maxDepth;
  }

  // A standard DTree with a few more bits.  Support for sampling during
  // training, and replaying the sample later on the identical dataset to
  // e.g. compute OOBEE.
  static class DRFTree extends DTree {
    final int _mtrys;           // Number of columns to choose amongst in splits
    final long _seed;           // RNG seed; drives sampling seeds
    final long _seeds[];        // One seed for each chunk, for sampling
    final transient Random _rand; // RNG for split decisions & sampling
    DRFTree( Frame fr, int ncols, int nclass, DHistogram hs[], int mtrys, long seed ) { 
      super(fr._names, ncols, nclass); 
      _mtrys = mtrys; 
      _seed = seed;                  // Save for any replay scenarios
      _rand = new MersenneTwisterRNG(new int[]{(int)(seed>>32),(int)seed});
      _seeds = new long[fr._vecs[0].nChunks()];
      for( int i=0; i<_seeds.length; i++ )
        _seeds[i] = _rand.nextLong();
      new DRFUndecidedNode(this,-1,hs); // The "root" node 
    }
    // Return a deterministic chunk-local RNG.  Can be kinda expensive.
    @Override public Random rngForChunk( int cidx ) {
      long seed = _seeds[cidx];
      return new MersenneTwisterRNG(new int[]{(int)(seed>>32),(int)seed});
    }
  }

  // DRF DTree decision node: same as the normal DecidedNode, but specifies a
  // decision algorithm given complete histograms on all columns.  
  // DRF algo: find the lowest error amongst a random mtry columns.
  static class DRFDecidedNode extends DecidedNode<DRFUndecidedNode> {
    DRFDecidedNode( DRFUndecidedNode n ) { super(n); }

    @Override DRFUndecidedNode makeUndecidedNode(DTree tree, int nid, DHistogram[] nhists ) { 
      return new DRFUndecidedNode(tree,nid,nhists); 
    }

    // Find the column with the best split (lowest score).
    @Override int bestCol( DRFUndecidedNode u ) {
      //DRFTree tree = (DRFTree)_tree;
      //int[] cols = new int[hs.length];
      //int len=0;
      //// Gather all active columns to choose from.  Ignore columns we
      //// previously ignored, or columns with 1 bin (nothing to split), or
      //// histogramed bin min==max (means the predictors are constant).
      //for( int i=0; i<hs.length; i++ ) {
      //  if( hs[i]==null || hs[i].nbins() <= 1 ) continue; // Ignore not-tracked cols, or cols with 1 bin (will not split)
      //  if( hs[i]._min == hs[i]._max ) continue; // predictor min==max, does not distinguish
      //  cols[len++] = i;        // Gather active column
      //}
      //
      //// Draw up to mtry columns at random without replacement.
      //// Take the best one.
      //double bs = Double.MAX_VALUE; // Best score
      //int idx = -1;                 // Column to split on
      //for( int i=0; i<tree._mtrys; i++ ) {
      //  if( len == 0 ) break;   // Out of choices!
      //  int idx2 = tree._rand.nextInt(len);
      //  int col = cols[idx2];     // The chosen column
      //  cols[idx2] = cols[--len]; // Compress out of array; do not choose again
      //  double s = hs[col].score();
      //  if( s < bs ) { bs = s; idx = col; }
      //  if( s <= 0 ) break;     // No point in looking further!
      //}
      double bs = Double.MAX_VALUE; // Best score
      int idx = -1;                 // Column to split on
      for( int i=0; i<u._scoreCols.length; i++ ) {
        int col = u._scoreCols[i];
        double s = u._hs[col].score();
        if( s < bs ) { bs = s; idx = col; }
        if( s <= 0 ) break;     // No point in looking further!
      }
      return idx;
    }
  }

  // DRF DTree undecided node: same as the normal UndecidedNode, but specifies
  // a list of columns to score on now, and then decide over later.
  // DRF algo: pick a random mtry columns
  static class DRFUndecidedNode extends UndecidedNode {
    DRFUndecidedNode( DTree tree, int pid, DHistogram hs[] ) { super(tree,pid,hs); }

    // Randomly select mtry columns to 'score' in following pass over the data.
    @Override int[] scoreCols( DHistogram[] hs ) {
      DRFTree tree = (DRFTree)_tree;
      int[] cols = new int[hs.length];
      int len=0;
      // Gather all active columns to choose from.  Ignore columns we
      // previously ignored, or columns with 1 bin (nothing to split), or
      // histogramed bin min==max (means the predictors are constant).
      for( int i=0; i<hs.length; i++ ) {
        if( hs[i]==null ) continue; // Ignore not-tracked cols
        if( hs[i]._min == hs[i]._max ) continue; // predictor min==max, does not distinguish
        if( hs[i] instanceof DBinHistogram && hs[i].nbins() <= 1 ) 
          continue;             // cols with 1 bin (will not split)
        cols[len++] = i;        // Gather active column
      }
      int choices = len;        // Number of columns I can choose from
      if( choices == 0 ) {
        for( int i=0; i<hs.length; i++ ) {
          String s;
          if( hs[i]==null ) s="null";
          else if( hs[i]._min == hs[i]._max ) s="min==max=="+hs[i]._min;
          else if( hs[i] instanceof DBinHistogram && hs[i].nbins() <= 1 ) 
            s="nbins="+hs[i].nbins();
          else s="unk";
          System.out.println(s);
        }
      }
      assert choices > 0;

      // Draw up to mtry columns at random without replacement.
      double bs = Double.MAX_VALUE; // Best score
      for( int i=0; i<tree._mtrys; i++ ) {
        if( len == 0 ) break;   // Out of choices!
        int idx2 = tree._rand.nextInt(len);
        int col = cols[idx2];     // The chosen column
        cols[idx2] = cols[--len]; // Compress out of array; do not choose again
        cols[len] = col;          // Swap chosen in just after 'len'
      }
      assert choices - len > 0;
      return Arrays.copyOfRange(cols,len,choices);
    }
  }

  // Determinstic sampling
  static class Sample extends MRTask2<Sample> {
    final DRFTree _tree;
    final float _rate;
    Sample( DRFTree tree, double rate ) { _tree = tree; _rate = (float)rate; }
    @Override public void map( Chunk nids ) {
      Random rand = _tree.rngForChunk(nids.cidx());
      for( int i=0; i<nids._len; i++ )
        if( rand.nextFloat() >= _rate )
          nids.set80(i,-2);     // Flag row as being ignored by sampling
    }
  }
}
