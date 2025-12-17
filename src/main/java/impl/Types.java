package impl;

import framework.lang.Type;
import java.util.ArrayList;
import java.util.List;

public class Types {
    public static class PrimitiveType implements Type {
        private final String name;

        public PrimitiveType(String name) {
            this.name = name;
        }

        @Override
        public String prettyPrint() {
            return name;
        }
    }

    public static class PointerType implements Type {
        private final Type ref;

        public PointerType(Type ref) {
            this.ref = ref;
        }

        @Override
        public String prettyPrint() {
            return ref.prettyPrint() + "*";
        }

        public Type getRef() { return ref; }
    }

    public static class ArrayType implements Type {
        private final Type element;
        private final int len;

        public ArrayType(Type element, int len) {
            this.element = element;
            this.len = len;
        }

        @Override
        public String prettyPrint() {
            return element.prettyPrint() + "[" + len + "]";
        }

        public Type getElement() { return element; }
        public int getLen() { return len; }
    }

    public static class FuncType implements Type {
        final Type ret;
        final List<Type> params = new ArrayList<>();

        public FuncType(Type ret) {
            this.ret = ret;
        }

        public void addParam(Type t) { params.add(t); }

        @Override
        public String prettyPrint() {
            StringBuilder sb = new StringBuilder();
            sb.append(ret.prettyPrint());
            sb.append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(params.get(i).prettyPrint());
            }
            sb.append(")");
            return sb.toString();
        }
    }

    public static class StructType implements Type {
        private final String tag;
        private boolean complete = false;
        private final List<Member> members = new ArrayList<>();

        public StructType(String tag) {
            this.tag = tag;
        }

        public String getTag() { return this.tag; }

        public void setComplete(boolean c) { this.complete = c; }
        public boolean isComplete() { return complete; }

        public void addMember(String name, Type type) { members.add(new Member(name, type)); }

        public boolean hasMember(String name) {
            for (Member m : members) {
                if (m.name.equals(name)) return true;
            }
            return false;
        }

        public Type getMemberType(String name) {
            for (Member m : members) {
                if (m.name.equals(name)) return m.type;
            }
            return null;
        }

        @Override
        public String prettyPrint() {
            return "struct " + tag;
        }

        @Override
        public String fullPrint() {
            StringBuilder sb = new StringBuilder();
            sb.append("struct ").append(tag).append("{");
            for (Member m : members) {
                sb.append(m.type.prettyPrint()).append(" ").append(m.name).append(";");
            }
            sb.append("}");
            return sb.toString();
        }

        public static class Member {
            public final String name;
            public final Type type;

            public Member(String name, Type type) {
                this.name = name;
                this.type = type;
            }
        }
    }
    public static boolean isEqual(Type a, Type b) {
        if (a == null || b == null) return false;
        if (a instanceof PrimitiveType && b instanceof PrimitiveType) {
            return ((PrimitiveType) a).name.equals(((PrimitiveType) b).name);
        }
        if (a instanceof PointerType && b instanceof PointerType) {
            return isEqual(((PointerType) a).ref, ((PointerType) b).ref);
        }
        if (a instanceof ArrayType && b instanceof ArrayType) {
            return ((ArrayType) a).len == ((ArrayType) b).len &&
                    isEqual(((ArrayType) a).element, ((ArrayType) b).element);
        }
        if (a instanceof StructType && b instanceof StructType) {
            return ((StructType) a).tag.equals(((StructType) b).tag);
        }
        return false;
    }
}
