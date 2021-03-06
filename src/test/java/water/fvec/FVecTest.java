package water.fvec;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.BeforeClass;
import org.junit.Test;

import water.*;
import water.parser.ParseDataset;

public class FVecTest extends TestUtil {
  static final double EPSILON = 1e-6;
  @BeforeClass public static void stall() { stall_till_cloudsize(1); }

  public static  Key makeByteVec(String kname, String... data) {
    Key k = Vec.newKey(Key.make(kname));
    byte [][] chunks = new byte[data.length][];
    long [] espc = new long[data.length+1];
    for(int i = 0; i < chunks.length; ++i){
      chunks[i] = data[i].getBytes();
      espc[i+1] = espc[i] + data[i].length();
    }
    Futures fs = new Futures();
    ByteVec bv = new ByteVec(k,espc);
    DKV.put(k, bv, fs);
    for(int i = 0; i < chunks.length; ++i){
      Key chunkKey = bv.chunkKey(i);
      DKV.put(chunkKey, new Value(chunkKey,chunks[i].length,chunks[i],TypeMap.C1NCHUNK,Value.ICE));
    }
    fs.blockForPending();
    return k;
  }

  // ==========================================================================
  @Test public void testBasicCRUD() {
    // Make and insert a FileVec to the global store
    File file = TestUtil.find_test_file("./smalldata/cars.csv");
    Key key = NFSFileVec.make(file);
    NFSFileVec nfs=DKV.get(key).get();

    int[] x = new ByteHisto().doAll(nfs)._x;
    int sum=0;
    for( int i : x )
      sum += i;
    assertEquals(file.length(),sum);

    UKV.remove(key);
  }

  public static class ByteHisto extends MRTask2<ByteHisto> {
    public int[] _x;
    // Count occurrences of bytes
    @Override public void map( Chunk bv ) {
      _x = new int[256];        // One-time set histogram array
      for( int i=0; i<bv._len; i++ )
        _x[(int)bv.at0(i)]++;
    }
    // ADD together all results
    @Override public void reduce( ByteHisto bh ) {
      for( int i=0; i<_x.length; i++ )
        _x[i] += bh._x[i];
    }
  }

  // ==========================================================================
  // Test making a appendable vector from a plain vector
  @Test public void testNewVec() {
    // Make and insert a File8Vec to the global store
    File file = TestUtil.find_test_file("./smalldata/cars.csv");
    //File file = TestUtil.find_test_file("../Dropbox/Sris and Cliff/H20_Rush_New_Dataset_100k.csv");
    Key key = NFSFileVec.make(file);
    NFSFileVec nfs=DKV.get(key).get();
    Key key2 = Key.make("newKey",(byte)0,Key.VEC);
    AppendableVec nv = new AppendableVec(key2);
    Vec res = new TestNewVec().doAll(nv,nfs).vecs(0);
    assertEquals(nfs.at8(0)+1,res.at8(0));
    assertEquals(nfs.at8(1)+1,res.at8(1));
    assertEquals(nfs.at8(2)+1,res.at8(2));

    UKV.remove(key );
    UKV.remove(key2);
  }

  public static class TestNewVec extends MRTask2<TestNewVec> {
    @Override public void map( NewChunk out, Chunk in ) {
      for( int i=0; i<in._len; i++ )
        out.append2( in.at8(i)+(in.at8(i) >= ' ' ? 1 : 0),0);
    }
  }

  // ==========================================================================
  @Test public void testParse() {
    //File file = TestUtil.find_test_file("./smalldata/airlines/allyears2k_headers.zip");
    //File file = TestUtil.find_test_file("../datasets/UCI/UCI-large/covtype/covtype.data");
    //File file = TestUtil.find_test_file("./smalldata/hhp.cut3.214.data.gz");
    File file = TestUtil.find_test_file("./smalldata/logreg/prostate_long.csv.gz");
    Key fkey = NFSFileVec.make(file);
    Key dest = Key.make("pro1.hex");
    Frame fr = ParseDataset2.parse(dest, new Key[]{fkey});
    UKV.remove(fkey);
    //System.out.println("Parsed into "+fr);
    //for( int i=0; i<fr._vecs.length; i++ )
    //  System.out.println("Vec "+i+" = "+fr._vecs[i]);

    Key rkey = load_test_file(file,"pro2.data");
    Key vkey = Key.make("pro2.hex");
    ParseDataset.parse(vkey, new Key[]{rkey});
    UKV.remove(rkey);
    ValueArray ary = UKV.get(vkey);
    //System.out.println("Parsed into "+ary);
    assertEquals(ary.numRows(),fr._vecs[0].length());

    try {
      int errs=0;
      long rows = ary.numRows();
      for( long i=0; i<rows; i++ ) {
        if( errs > 1 ) break;
        for( int j=0; j<ary._cols.length; j++ ) {
          double d1 = fr._vecs[j].at(i);
          double d2 = ary.datad(i,j);
          if( Math.abs((d1-d2)/d1) > 0.0000001  ) {
            System.out.println("Row "+i);
            System.out.println("FVec= "+fr .toString(i));
            System.out.println("VAry= "+ary.toString(i));
            errs++;
            break;
          }
        }
      }
      assertEquals(0,errs);
    } finally {
      UKV.remove(dest);
      UKV.remove(ary._key);
    }
  }

  // ==========================================================================
  @Test public void testParse2() {
    File file = TestUtil.find_test_file("../smalldata/logreg/syn_2659x1049.csv");
    Key fkey = NFSFileVec.make(file);

    Key okey = Key.make("syn.hex");
    Frame fr = ParseDataset2.parse(okey,new Key[]{fkey});
    UKV.remove(fkey);
    try {
      assertEquals(fr.numCols(),1050); // Count of columns
      assertEquals(fr._vecs[0].length(),2659); // Count of rows

      double[] sums = new Sum().doAll(fr)._sums;
      assertEquals(3949,sums[0],EPSILON);
      assertEquals(3986,sums[1],EPSILON);
      assertEquals(3993,sums[2],EPSILON);

      // Create a temp column of zeros
      Vec v0 = fr._vecs[0];
      Vec v1 = fr._vecs[1];
      Vec vz = v0.makeZero();
      // Add column 0 & 1 into the temp column
      new PairSum().doAll(vz,v0,v1);
      // Add the temp to frame
      // Now total the temp col
      fr.remove();              // Remove all other columns
      fr.add("tmp",vz);         // Add just this one
      sums = new Sum().doAll(fr)._sums;
      assertEquals(3949+3986,sums[0],EPSILON);

    } finally {
      fr.remove();
      UKV.remove(okey);
    }
  }

  // Sum each column independently
  private static class Sum extends MRTask2<Sum> {
    double _sums[];
    @Override public void map( Chunk[] bvs ) {
      _sums = new double[bvs.length];
      int len = bvs[0]._len;
      for( int i=0; i<len; i++ )
        for( int j=0; j<bvs.length; j++ )
          _sums[j] += bvs[j].at0(i);
    }
    @Override public void reduce( Sum mrt ) {
      for( int j=0; j<_sums.length; j++ )
        _sums[j] += mrt._sums[j];
    }
  }

  // Simple vector sum C=A+B
  private static class PairSum extends MRTask2<Sum> {
    @Override public void map( Chunk out, Chunk in1, Chunk in2 ) {
      for( int i=0; i<out._len; i++ )
        out.set80(i,in1.at80(i)+in2.at80(i));
    }
  }
}

