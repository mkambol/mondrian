package mondrian.olap;

import mondrian.mdx.*;

import java.util.*;

public class IdentifierVisitor extends MdxVisitorImpl {
    private final Set<Id> identifiers;

    public IdentifierVisitor(Set<Id> identifiers) {
        this.identifiers = identifiers;
    }

    public Object visit(Id id) {
        identifiers.add(id);
        return null;
    }
}
