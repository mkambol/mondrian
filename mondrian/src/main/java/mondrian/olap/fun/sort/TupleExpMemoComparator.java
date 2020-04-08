/*
 *
 * // This software is subject to the terms of the Eclipse Public License v1.0
 * // Agreement, available at the following URL:
 * // http://www.eclipse.org/legal/epl-v10.html.
 * // You must accept the terms of that agreement to use this software.
 * //
 * // Copyright (C) 2001-2005 Julian Hyde
 * // Copyright (C) 2005-2020 Hitachi Vantara and others
 * // All Rights Reserved.
 * /
 *
 */

package mondrian.olap.fun.sort;

import mondrian.calc.Calc;
import mondrian.calc.TupleList;
import mondrian.olap.Evaluator;
import mondrian.olap.Member;
import mondrian.olap.Util;
import mondrian.rolap.agg.CellRequestQuantumExceededException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// almost the same as MemberComparator
abstract class TupleExpMemoComparator extends TupleComparator.TupleExpComparator {
  private final Map<List<Member>, Object> valueMap =
    new HashMap<List<Member>, Object>();

  TupleExpMemoComparator( Evaluator e, Calc calc, int arity ) {
    super( e, calc, arity );
  }

  // applies the Calc to a tuple, memorizing results
  protected Object eval( List<Member> t ) {
    Object val = valueMap.get( t );
    if ( val != null ) {
      return val;
    }
    return compute( t );
  }

  private Object compute( List<Member> key ) {
    evaluator.setContext( key );
    Object val = calc.evaluate( evaluator );
    if ( val == null ) {
      val = Util.nullValue;
    }
    valueMap.put( key, val );
    return val;
  }

  // Preloads the value map by applying the expression to a Collection of
  // members.
  void preloadValues( TupleList tuples ) {
    for ( List<Member> t : tuples ) {
      compute( t );
    }
  }

  static class BreakTupleComparator extends TupleExpMemoComparator {
    BreakTupleComparator( Evaluator e, Calc calc, int arity ) {
      super( e, calc, arity );
    }

    public int compare( List<Member> a1, List<Member> a2 ) {
      return Sorter.compareValues( eval( a1 ), eval( a2 ) );
    }
  }
}
