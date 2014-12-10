package mondrian.olap.fun;

import mondrian.mdx.*;
import mondrian.olap.*;
import mondrian.olap.type.Type;

import java.util.Set;


public class MemberVisitor extends MdxVisitorImpl {

    private final Set<Member> dimensionMemberSet;

    public MemberVisitor(Set<Member> dimensionMemberSet)
    {
        this.dimensionMemberSet = dimensionMemberSet;
    }

    public Object visit(ParameterExpr parameterExpr) {
        final Parameter parameter = parameterExpr.getParameter();
        final Type type = parameter.getType();
        if (type instanceof mondrian.olap.type.MemberType) {
            final Object value = parameter.getValue();
            if (value instanceof Member) {
                final Member member = (Member) value;
                if (!member.isMeasure() && !member.isCalculated()) {
                    dimensionMemberSet.add(member);
                }
            }
        }
        return null;
    }

    public Object visit(MemberExpr memberExpr) {
        Member member = memberExpr.getMember();
        if (!member.isMeasure() && !member.isCalculated()) {
            dimensionMemberSet.add(member);
        }
        return null;
    }

    public Object visit(DimensionExpr dimensionExpr) {
        addToDimMemberSet( dimensionExpr.getDimension().getHierarchy() );
        return null;
    }

    private void addToDimMemberSet(Hierarchy hierarchy) {
        if (!hierarchy.getDimension().isMeasures()) {
            dimensionMemberSet.add( hierarchy.getAllMember());
        }
    }

    public Object visit(HierarchyExpr hierarchyExpr) {
        addToDimMemberSet(hierarchyExpr.getHierarchy());
        return null;
    }

    public Object visit(LevelExpr levelExpr) {
        addToDimMemberSet(levelExpr.getLevel().getHierarchy());
        return null;
    }

}
