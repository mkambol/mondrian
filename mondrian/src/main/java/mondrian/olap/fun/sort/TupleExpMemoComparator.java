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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import mondrian.calc.Calc;
import mondrian.olap.Evaluator;
import mondrian.olap.Member;
import mondrian.olap.Util;
import mondrian.rolap.agg.CellRequestQuantumExceededException;
import mondrian.util.CancellationChecker;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

// almost the same as MemberComparator
abstract class TupleExpMemoComparator extends TupleComparator.TupleExpComparator {
  Cache<List<Member>, Object> valueCache = CacheBuilder.newBuilder().maximumSize( 100000 ).build();

  private int[] dependentHierarchiesIndices;
  private int count = 0;

  TupleExpMemoComparator( Evaluator e, Calc calc, int arity ) {
    super( e, calc, arity );
  }

  // applies the Calc to a tuple, memorizing results
  protected Object eval( List<Member> key ) {
    try {
      return valueCache.get( key, () -> evaluateCalc( key ) );
    } catch ( UncheckedExecutionException e ) {
      if ( e.getCause() instanceof CellRequestQuantumExceededException ) {
        // the guava Cache wraps the exception, but we want this one to percolate up.
        throw CellRequestQuantumExceededException.INSTANCE;
      }
      throw e;
    } catch ( ExecutionException e ) {
      // todo log
      return evaluateCalc( key );
    }
  }

  private List<Member> dependentMembers( List<Member> tuple ) {
    getDependentHierarchiesIndices( tuple );
    return Arrays.stream( dependentHierarchiesIndices )
      .mapToObj( tuple::get )
      .collect( Collectors.toList() );
  }

  private void getDependentHierarchiesIndices( List<Member> tuple ) {
    if ( dependentHierarchiesIndices == null ) {
      dependentHierarchiesIndices = new int[ tuple.size() ];
      int curPos = 0;
      for ( int i = 0; i < tuple.size(); i++ ) {
        if ( calc.dependsOn( tuple.get( i ).getHierarchy() ) ) {
          dependentHierarchiesIndices[ curPos++ ] = i;
        }
      }
      dependentHierarchiesIndices = Arrays.copyOf( dependentHierarchiesIndices, curPos );
    }
  }

  public int compare( List<Member> a1, List<Member> a2 ) {
    CancellationChecker.checkCancelOrTimeout( count++,
      evaluator.getQuery().getStatement().getCurrentExecution() );
    List<Member> a1Members = dependentMembers( a1 );
    List<Member> a2Members = dependentMembers( a2 );
    return Sorter.listEquals( a1Members, a2Members ) ? 0
      : nonEqualCompare( a1Members, a2Members );
  }


  protected abstract int nonEqualCompare( List<Member> eval, List<Member> eval1 );

  private Object evaluateCalc( List<Member> tuple ) {
    evaluator.setContext( tuple );
    Object val = calc.evaluate( evaluator );
    return val == null ? Util.nullValue : val;
  }

  static class BreakTupleComparator extends TupleExpMemoComparator {
    BreakTupleComparator( Evaluator e, Calc calc, int arity ) {
      super( e, calc, arity );
    }

    @Override protected int nonEqualCompare( List<Member> a1, List<Member> a2 ) {
      return Sorter.compareValues( eval( a1 ), eval( a2 ) );
    }
  }
}
