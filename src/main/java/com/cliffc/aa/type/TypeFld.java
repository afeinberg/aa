package com.cliffc.aa.type;

import com.cliffc.aa.util.SB;
import com.cliffc.aa.util.Util;
import com.cliffc.aa.util.VBitSet;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;


// A field in a TypeStruct, with a type and a name and an Access.  Field
// accesses make a small lattice of: {choice,r/w,final,r-o}.  Note that mixing
// r/w and final moves to r-o and loses the final property.  No field order.
public class TypeFld extends Type<TypeFld> {
  // Field names are never null, and never zero-length.  If the 1st char is a
  // '*' the field is Top; a '.' is Bot; all other values are valid field names.
  public String _fld;           // The field name
  public Type _t;               // Field type.  Usually some type of Scalar, or ANY or ALL.
  public Access _access;        // Field access type: read/write, final, read/only

  private TypeFld init( @NotNull String fld, Type t, Access access ) {
    super.init(TFLD,"");
    assert !(t instanceof TypeFld);
    _fld=fld; _t=t; _access=access;
    _hash = 0;
    return this;
  }

  @Override public int compute_hash() { return _fld.hashCode()+_t.hashCode()+_access.hashCode(); }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeFld) ) return false;
    TypeFld t = (TypeFld)o;
    // Check for obviously equals or not-equals
    int x = cmp(t);
    if( x != -1 ) return x == 1;
    // Needs a cycle check
    return cycle_equals(t);
  }

  // Returns 1 for definitely equals, 0 for definitely unequals and -1 for needing the circular test.
  int cmp(TypeFld t) {
    if( this==t ) return 1;
    if( !Util.eq(_fld,t._fld) || _access!=t._access ) return 0; // Definitely not equals without recursion
    if( _t==t._t ) return 1;    // All fields bitwise equals.
    if( _t==null || t._t==null ) return 0; // Mid-construction (during cycle building)
    if( _t._type!=t._t._type ) return 0; // Last chance to avoid cycle check; types have a chance of being equal
    // Some type pointer-not-equals, needs a cycle check
    return -1;
  }
  @Override public boolean cycle_equals( Type o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeFld) ) return false;
    TypeFld t2 = (TypeFld)o;
    if( !Util.eq(_fld,t2._fld) ||  _access!=t2._access ) return false;
    return _t == t2._t || _t.cycle_equals(t2._t);
  }

  @Override public SB str( SB sb, VBitSet dups, TypeMem mem, boolean debug ) {
    if( dups.tset(_uid) ) return sb.p('$'); // Break recursive printing cycle
    if( !TypeStruct.isDigit(_fld.charAt(0)) ) // Do not print number-named fields for tuples
      _access.str(sb.p(_fld));
    return _t==null ? sb.p('!') : (_t==Type.SCALAR ? sb : _t.str(sb,dups,mem,debug));
  }

  static { new Pool(TFLD,new TypeFld()); }
  static TypeFld malloc( String fld, Type t, Access access ) { return POOLS[TFLD].<TypeFld>malloc().init(fld,t,access); }
  public static TypeFld malloc( String fld ) { return POOLS[TFLD].<TypeFld>malloc().init(fld,null,Access.Final); }
  public static TypeFld make( String fld, Type t, Access access ) { return malloc(fld,t,access).hashcons_free(); }
  public static TypeFld make( String fld, Type t ) { return make(fld,t,Access.Final); }
  // Make a not-interned version for building cyclic types
  TypeFld malloc_from() { return malloc(_fld,_t,_access); }

  // Some convenient default constructors
  private static final String[] ARGS = new String[]{"^","x","y","z"};
  private static final String[] TUPS = new String[]{"^","0","1","2"};
  public static TypeFld make_arg( Type t, int order ) { return make(ARGS[order],t,Access.Final);  }
  public static TypeFld make_tup( Type t, int order ) { return make(TUPS[order],t,Access.Final);  }
  public TypeFld make_from(Type t) { return t==_t ? this : make(_fld,t,_access); }
  public TypeFld make_from(Type t, Access a) { return (t==_t && a==_access) ? this : make(_fld,t,a); }

  @Override protected TypeFld xdual() { return new TypeFld().init(sdual(_fld),_t._dual,_access.dual()); }
  @Override protected TypeFld rdual() {
    assert _hash!=0;
    if( _dual != null ) return _dual;
    TypeFld dual = _dual = new TypeFld().init(sdual(_fld),_t==null ? null : _t.rdual(),_access.dual());
    dual._dual = this;
    dual._hash = dual.compute_hash();
    return dual;
  }

  @Override protected TypeFld xmeet( Type tf ) {
    if( this==tf ) return this;
    if( tf._type != TFLD ) throw typerr(tf);
    TypeFld f = (TypeFld)tf;
    String fld   = smeet(_fld,  f._fld)  ;
    Type   t     = _t     .meet(f._t     );
    Access access= _access.meet(f._access);
    return make(fld,t,access);
  }

  private static TypeFld malloc( String fld, Access a ) {
    TypeFld tfld = POOLS[TFLD].malloc();
    return tfld.init(fld,null,a);
  }


  // Used during cyclic struct meets, either side (but not both) might be null,
  // and the _t field is not filled in.  A new TypeFld is returned.
  static TypeFld cmeet(TypeFld f0, TypeFld f1) {
    if( f0==null ) return malloc(f1._fld,f1._access);
    if( f1==null ) return malloc(f0._fld,f0._access);
    String fld   = smeet(f0._fld,  f1._fld);
    Access access= f0._access.meet(f1._access);
    return malloc(fld,access);
  }
  // Used during cyclic struct meets.  The LHS is meeted into directly.
  // The _t field is not filled in.
  void cmeet(TypeFld f) {
    assert _hash==0; // Not interned, hash is changing
    _fld = smeet(_fld,f._fld);
    _access = _access.meet(f._access);
  }

  public enum Access {
    ReadOnly,                   // Read-Only; other threads can Write
    RW,                         // Read/Write
    Final,                      // No future load will ever see a different value than any final store
    NoAccess,                   // Cannot access (either read or write)
    HiReadWrite,
    HiFinal,
    HiNoAccess;
    public static final Access[] values = values();
    static Access bot() { return ReadOnly; }
    Access dual() { return values[("6453120".charAt(ordinal()))-'0']; }
    private static final String[] FMEET = {
      /*    0123456 */
      /*0*/"0000000",
      /*1*/"0101111",
      /*2*/"0022222",
      /*3*/"0123333",
      /*4*/"0123434",
      /*5*/"0123355",
      /*6*/"0123456",
    };
    Access meet(Access a) { return values[FMEET[ordinal()].charAt(a.ordinal())-'0']; }
    private static final String[] SHORTS = new String[]{"==",":=","=","~=","!:=!","!=!","!~=!"};
    private static final String[] LONGS  = new String[]{"read-only","read/write","final","noaccess","!:=!","!=!","!~=!"};
    @Override public String toString() { return LONGS[ordinal()]; }
    public SB str(SB sb) { return sb.p(SHORTS[ordinal()]); }
  }

  // Field names
  public static final String fldTop = "\\";
  public static final String fldBot = "." ;
  // String dual
  private static String sdual(String s) {
    if( Util.eq(s,fldTop) ) return fldBot;
    if( Util.eq(s,fldBot) ) return fldTop;
    return s;
  }
  // String meet
  private static String smeet( String s0, String s1 ) {
    if( Util.eq(s0,s1) ) return s0;
    if( Util.eq(s0,fldTop) ) return s1;
    if( Util.eq(s1,fldTop) ) return s0;
    return fldBot;
  }

  public static final TypeFld NO_DISP = make("^",Type.ANY,Access.Final);

  // Setting the type during recursive construction.
  public TypeFld setX(Type t) {
    assert !(t instanceof TypeFld);
    if( _t==t) return this; // No change
    _t = t;
    assert _hash==0;  // Not hashed, since hash just changed
    return this;
  }
  public TypeFld setX(Type t, Access access) {
    assert !(t instanceof TypeFld);
    if( _t==t && _access==access ) return this; // No change
    assert _dual==null;     // Not interned
    _t = t;
    _access = access;
    return this;
  }

  // If this is a display field
  @Override public boolean is_display_ptr() { return Util.eq(_fld,"^") && _t.is_display_ptr(); }

  @Override public TypeFld simple_ptr() { return make_from(_t.simple_ptr()); }
  @SuppressWarnings("unchecked")
  @Override public void walk( Predicate<Type> p ) { if( p.test(this) ) _t.walk(p); }

  // Make a Type, replacing all dull pointers from the matching types in mem.
  @Override public TypeFld make_from(Type head, TypeMem mem, VBitSet visit) {
    return setX(_t.make_from(head,mem,visit)).hashcons_free();
  }

  @Override TypeStruct repeats_in_cycles(TypeStruct head, VBitSet bs) { return _t.repeats_in_cycles(head,bs); }

  // Used for assertions
  @Override boolean intern_check1() { return _t.intern_lookup()!=null; }

}

