package water;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

import water.api.Request;
import water.api.RequestArguments;
import water.fvec.Frame;

public abstract class Request2 extends Request {
  @Override protected void registered() {
    try {
      ArrayList<Class> classes = new ArrayList<Class>();
      {
        Class c = getClass();
        while( c != null ) {
          classes.add(c);
          c = c.getSuperclass();
        }
      }
      // Fields from parent classes first
      Collections.reverse(classes);
      ArrayList<Field> fields = new ArrayList<Field>();
      for( Class c : classes )
        for( Field field : c.getDeclaredFields() )
          if( !Modifier.isStatic(field.getModifiers()) )
            fields.add(field);

      for( Field f : fields ) {
        Annotation[] as = f.getAnnotations();
        API api = find(as, API.class);

        if( api != null && Helper.isInput(api) ) {
          f.setAccessible(true);
          Object defaultValue = f.get(this);

          // Create an Argument instance to reuse existing Web framework for now
          Argument arg = null;

          if( Argument.class.isAssignableFrom(api.filter()) )
            arg = (Argument) newInstance(api);
          else {
            // Real
            if( f.getType() == float.class || f.getType() == double.class ) {
              double val = ((Number) defaultValue).doubleValue();
              arg = new Real(f.getName(), api.required(), val, null, null, api.help());
            }

            // LongInt
            else if( f.getType() == int.class || f.getType() == long.class ) {
              long val = ((Number) defaultValue).longValue();
              arg = new LongInt(f.getName(), api.required(), val, null, null, api.help());
            }

            // Bool
            else if( f.getType() == boolean.class ) {
              boolean val = (Boolean) defaultValue;
              arg = new Bool(f.getName(), val, api.help());
            }

            // Key
            else if( f.getType() == Key.class )
              arg = new H2OKey(f.getName(), api.required());
          }

          if( ColumnSelect.class.isAssignableFrom(api.filter()) ) {
            ColumnSelect name = (ColumnSelect) newInstance(api);
            H2OHexKey key = null;
            for( Argument a : _arguments )
              if( a instanceof H2OHexKey && name._key.equals(((H2OHexKey) a)._name) )
                key = (H2OHexKey) a;
            arg = new HexAllColumnSelect(f.getName(), key);
          }
          if( VecSelect.class.isAssignableFrom(api.filter()) ) {
            VecSelect name = (VecSelect) newInstance(api);
            FrameKey key = null;
            for( Argument a : _arguments )
              if( a instanceof FrameKey && name._key.equals(((FrameKey) a)._name) )
                key = (FrameKey) a;
            arg = new FrameKeyVec(f.getName(), key);
          }

          if( arg != null ) {
            arg._name = f.getName();
            arg._required = api.required();
            arg._field = f;
          }
        }
      }
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }

  // Extracted in separate class as Weaver cannot load REquest during boot
  static final class Helper {
    static boolean isInput(API api) {
      return api.filter() != Filter.class || api.filters().length != 0;
    }
  }

  private static <T> T find(Annotation[] as, Class<T> c) {
    for( Annotation a : as )
      if( a.annotationType() == c )
        return (T) a;
    return null;
  }

  private Filter newInstance(API api) throws Exception {
    for( Constructor c : api.filter().getDeclaredConstructors() ) {
      c.setAccessible(true);
      Class[] ps = c.getParameterTypes();
      if( ps.length == 1 && RequestArguments.class.isAssignableFrom(ps[0]) )
        return (Filter) c.newInstance(this);
    }
    for( Constructor c : api.filter().getDeclaredConstructors() ) {
      Class[] ps = c.getParameterTypes();
      if( ps.length == 0 )
        return (Filter) c.newInstance();
    }
    throw new Exception("Class " + api.filter().getName() + " must have an empty constructor");
  }

  // Create an instance per call instead of ThreadLocals
  @Override protected Request create(Properties parms) {
    Request2 request;
    try {
      request = getClass().newInstance();
      request._arguments = _arguments;
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
    return request;
  }

  public void set(Argument arg, Object value) {
    try {
      if( arg._field.getType() == Key.class && value instanceof ValueArray )
        value = ((ValueArray) value)._key;
      if( arg._field.getType() == Frame.class && value instanceof Key )
        value = UKV.get((Key) value);
      if( arg._field.getType() == int.class && value instanceof Long )
        value = ((Long) value).intValue();
      if( arg._field.getType() == float.class && value instanceof Double )
        value = ((Double) value).floatValue();
      if( value instanceof NumberSequence ) {
        double[] ds = ((NumberSequence) value)._arr;
        if( arg._field.getType() == int[].class ) {
          int[] is = new int[ds.length];
          for( int i = 0; i < is.length; i++ )
            is[i] = (int) ds[i];
          value = is;
        } else
          value = ds;
      }
      arg._field.set(this, value);
    } catch( Exception e ) {
      throw new RuntimeException(e);
    }
  }
}
