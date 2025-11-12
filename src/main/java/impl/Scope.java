package impl;

import framework.lang.Type;
import java.util.LinkedHashMap;
import java.util.Map;

public class Scope {
    private final Scope parent;
    // name -> Type (for variables and functions)
    private final Map<String, Type> symbols = new LinkedHashMap<>();
    // struct tags in this scope
    private final Map<String, Types.StructType> tags = new LinkedHashMap<>();

    public Scope(Scope parent) { this.parent = parent; }

    public Scope parent() { return parent; }

    public Type lookup(String name) {
        Type t = symbols.get(name);
        if (t != null) return t;
        if (parent != null) return parent.lookup(name);
        return null;
    }

    public boolean containsHere(String name) { return symbols.containsKey(name); }

    public void define(String name, Type t) { symbols.put(name, t); }

    public Map<String, Type> getSymbols() { return symbols; }

    public Types.StructType lookupTag(String tag) {
        Types.StructType s = tags.get(tag);
        if (s != null) return s;
        if (parent != null) return parent.lookupTag(tag);
        return null;
    }

    public void defineTag(String tag, Types.StructType s) { tags.put(tag, s); }

    public boolean hasTagHere(String tag) { return tags.containsKey(tag); }
}
