/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (c) 2002-2014 Pentaho Corporation..  All rights reserved.
*/
package mondrian.olap.fun;

import mondrian.mdx.*;
import mondrian.olap.*;
import mondrian.olap.type.Type;

import java.util.*;

/**
 * Visitor which collects any non-measure base members encountered while
 * traversing an expression.
 * If a DimensionExpr, HierarchyExpr, or LevelExpr is encountered, the
 * All member of the corresponding hierarchy is added to the set of members.
 */
public class MemberVisitor extends MdxVisitorImpl {

    private final Set<Member> memberSet;
    private final ResolvedFunCallFinder finder;
    private final Set<Member> activeMembers = new HashSet<Member>();
    private final ResolvedFunCall call;
    private boolean encounteredCall = false;

    public MemberVisitor(Set<Member> memberSet, ResolvedFunCall call)
    {
        this.memberSet = memberSet;
        this.finder = new ResolvedFunCallFinder(call);
        this.call = call;
    }

    public Object visit(ParameterExpr parameterExpr) {
        final Parameter parameter = parameterExpr.getParameter();
        final Type type = parameter.getType();
        if (type instanceof mondrian.olap.type.MemberType) {
            final Object value = parameter.getValue();
            if (value instanceof Member) {
                final Member member = (Member) value;
                if (!member.isMeasure() && !member.isCalculated()) {
                    memberSet.add(member);
                }
            } else {
               parameter.getDefaultExp().accept(this);
            }
        }
        return null;
    }

    public Object visit(MemberExpr memberExpr) {
        Member member = memberExpr.getMember();
        if (!member.isMeasure() && !member.isCalculated()) {
            memberSet.add(member);
        } else if (member.isCalculated()) {
            if (activeMembers.add(member)) {
                Exp exp = member.getExpression();
                finder.found = false;
                exp.accept(finder);
                if (! finder.found) {
                    exp.accept(this);
                } else {
                    encounteredCall = true;
                }
                activeMembers.remove(member);
            }
        }
        return null;
    }

    public Object visit(DimensionExpr dimensionExpr) {
        // add the default hierarchy
        addToDimMemberSet(dimensionExpr.getDimension().getHierarchy());
        return null;
    }

    public Object visit(HierarchyExpr hierarchyExpr) {
        addToDimMemberSet(hierarchyExpr.getHierarchy());
        return null;
    }

    public Object visit(LevelExpr levelExpr) {
        addToDimMemberSet(levelExpr.getLevel().getHierarchy());
        return null;
    }

    public Object visit(ResolvedFunCall funCall) {
        if (funCall == call) {
            encounteredCall = true;
            turnOffVisitChildren();
        }
        return null;
    }

    private void addToDimMemberSet(Hierarchy hierarchy) {
        if (!hierarchy.getDimension().isMeasures()) {
            memberSet.add(hierarchy.getAllMember());
        }
    }

    public boolean encounteredCall() {
        return encounteredCall;
    }
}

// End MemberVisitor.java