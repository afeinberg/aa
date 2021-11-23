package com.cliffc.aa.HM;

import com.cliffc.aa.type.*;
import com.cliffc.aa.util.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static com.cliffc.aa.AA.ARG_IDX;
import static com.cliffc.aa.AA.unimpl;

// Combined Hindley-Milner and Global Constant Propagation typing.

// Complete stand-alone, for research.

// Treats HM as a Monotone Analysis Framework; converted to a worklist style.
// The type-vars are monotonically unified, gradually growing over time - and
// this is treated as the MAF lattice.  Some normal Algo-W work gets done in a
// prepass; e.g. discovering identifier sources (SSA form), and building the
// non-generative set.  Because of the non-local unification behavior type-vars
// include a "dependent Syntax" set; a set of Syntax elements put back on the
// worklist if this type unifies, beyond the expected parent and AST children.
//
// The normal HM unification steps are treated as the MAF transfer "functions",
// taking type-vars as inputs and producing new, unified, type-vars.  Because
// unification happens in-place (normal Tarjan disjoint-set union), the
// transfer "functions" are executed for side effects only, and return a
// progress flag.  The transfer functions are virtual calls on each Syntax
// element.  Some steps are empty because of the pre-pass (Let,Con).
//
// HM Bases include anything from the GCP lattice, and are generally sharper
// than e.g. 'int'.  Bases with values of '3' and "abc" are fine.  These are
// widened to the normal HM types if passed to any HM function; they remain
// sharp if returned or passed to primitives.  HM functions include the set of
// FIDXs used in the unification; this set is generally less precise than that
// from GCP.  HM function arguments that escape had their GCP type widened "as
// if" called from the most HM-general legal call site; otherwise GCP assumes
// escaping functions are never called and their arguments have unrealistic
// high flow types.
//
// HM includes polymorphic structures and fields (structural typing not duck
// typing), polymorphic nil-checking and an error type-var.  Both HM and GCP
// types fully support recursive types.
//
// HM errors keep all the not-unifiable types, all at once.  Further unifications
// with the error either add a new not-unifiable type, or unify with one of the
// prior types.  These means that types can ALWAYS unify, including nonsensical
// unifications between e.g. the constant 5 and a struct @{ x,y }.  The errors
// are reported when a type prints.
//
// Unification typically makes many temporary type-vars and immediately unifies
// them.  For efficiency, this algorithm checks to see if unification requires
// an allocation first, instead of just "allocate and unify".  The major place
// this happens is identifiers, which normally make a "fresh" copy of their
// type-var, then unify.  I use a combined "make-fresh-and-unify" unification
// algorithm there.  It is a structural clone of the normal unify, except that
// it lazily makes a fresh-copy of the left-hand-side on demand only; typically
// discovering that no fresh-copy is required.
//
// To engineer and debug the algorithm, the unification step includes a flag to
// mean "actually unify, and report a progress flag" vs "report if progress".
// The report-only mode is aggressively asserted for in the main loop; all
// Syntax elements that can make progress are asserted as on the worklist.
//
// GCP gets the normal MAF treatment, no surprises there.
//
// The combined algorithm includes transfer functions taking facts from both
// MAF lattices, producing results in the other lattice.
//
// For the GCP->HM direction, the HM 'if' has a custom transfer function
// instead of the usual one.  Unification looks at the GCP value, and unifies
// either the true arm, or the false arm, or both or neither.  In this way GCP
// allows HM to avoid picking up constraints from dead code.
//
// Also for GCP->HM, the HM ground terms or base terms include anything from
// the GCP lattice.
//
// For the HM->GCP direction, the GCP 'apply' has a customer transfer function
// where the result from a call gets lifted (JOINed) based on the matching GCP
// inputs - and the match comes from using the same HM type-var on both inputs
// and outputs.  This allows e.g. "map" calls which typically merge many GCP
// values at many applies (call sites) and thus end up typed as a Scalar to
// Scalar, to improve the GCP type on a per-call-site basis.
//
// Test case 45 demonstrates this combined algorithm, with a program which can
// only be typed using the combination of GCP and HM.
//
// BNF for the "core AA" syntax:
//    e  = number | string | primitives | (fe0 fe1*) | { id* -> fe0 } | id | id = fe0; fe1 | @{ (label = fe0)* }
//    fe = e | fe.label                 // optional field after expression
//
// BNF for the "core AA" pretty-printed types:
//    T = X | X:T | { X* -> X } | base | @{ (label = X)* } | T? | Error
//    base = any lattice element, all are nilable
//    Multiple stacked T????s collapse
//


public class HM {
  // Mapping from primitive name to PrimSyn
  static final HashMap<String,PrimSyn> PRIMSYNS = new HashMap<>();
  // Precision of cyclic GCP types
  static final int CUTOFF=1;

  static { BitsAlias.init0(); BitsFun.init0(); }

  static final boolean DO_NOTNIL=false;
  static boolean DO_TVARS;      // Set by tests; partition split polymorphic type vars
  static boolean DO_GCP;        // Set by tests

  public static Root hm( String sprog, int rseed, boolean do_tvars, boolean do_gcp ) {
    Type.RECURSIVE_MEET=0;      // Reset between failed tests
    DO_TVARS= do_tvars;
    DO_GCP  = do_gcp;

    for( PrimSyn prim : new PrimSyn[]{new If(), new Pair(), new EQ(), new EQ0(), new Mul(), new Add(), new Dec(), new Str(), new Triple(), new Factor(), new IsEmpty(), new NotNil()} )
      PRIMSYNS.put(prim.name(),prim);

    // Parse
    Root prog = parse( sprog );

    // Pass 0: Prep for SSA; pre-gather all the (unique) ids
    Worklist work = new Worklist(rseed);
    int cnt_syns = prog.prep_tree(null,null,work);
    int work_cnt=0;
    int init_T2s = T2.CNT;  // Profiling bit

    // Start every flow type at ANY and all TVars in the same partition
    work_cnt += main_work_loop(prog,work);
    assert prog.more_work(work);

    // Pass 2: Give up on the Root GCP arg types.  Drop them to the best Root
    // approximation and never lift again.
    prog.update_root_args(work);
    work_cnt += main_work_loop(prog,work);
    assert prog.more_work(work);

    // Profiling print
    System.out.println("Initial T2s: "+init_T2s+", Prog size: "+cnt_syns+", worklist iters: "+work_cnt+", T2s: "+(init_T2s-T2.CNT));
    return prog;
  }

  static int main_work_loop(Root prog, Worklist work) {

    int cnt=0;
    while( work.len()>0 ) {     // While work
      int oldcnt = T2.CNT;      // Used for cost-check when no-progress
      cnt++; assert cnt<10000;  // Check for infinite loops
      Syntax syn = work.pop();  // Get work

      // Do Global Constant Propagation work
      if( DO_GCP ) {
        Type old = syn._flow;
        Type t = syn.val(work);
        if( t!=old ) {           // Progress
          assert old.isa(t);     // Monotonic falling
          syn._flow = t;         // Update type
          if( syn._par!=null )   // Generally, parent needs revisit
            syn._par.add_val_work(syn,work); // Push affected neighbors on worklist
        }
      }

      // VERY EXPENSIVE ASSERT: O(n^2).  Every Syntax that makes progress is on the worklist
      assert prog.more_work(work);
    }
    return cnt;
  }

  // Reset global statics between tests
  static void reset() {
    BitsAlias.reset_to_init0();
    BitsFun.reset_to_init0();
    PRIMSYNS.clear();
    Lambda.FUNS.clear();
    T2.reset();
    PrimSyn.reset();
  }

  // ---------------------------------------------------------------------
  // Program text for parsing
  private static int X;
  private static byte[] BUF;
  @Override public String toString() { return new String(BUF,X,BUF.length-X); }
  static Root parse( String s ) {
    X = 0;
    BUF = s.getBytes();
    Syntax prog = fterm();
    if( skipWS() != -1 ) throw unimpl("Junk at end of program: "+new String(BUF,X,BUF.length-X));
    // Inject IF at root
    return new Root(prog);
  }
  static Syntax term() {
    if( skipWS()==-1 ) return null;
    if( isDigit(BUF[X]) ) return number();
    if( BUF[X]=='"' ) return string();

    if( BUF[X]=='(' ) {         // Parse an Apply
      X++;                      // Skip paren
      Syntax fun = fterm();
      Ary<Syntax> args = new Ary<>(new Syntax[1],0);
      while( skipWS()!= ')' && X<BUF.length ) args.push(fterm());
      require(')');
      // Guarding if-nil test inserts an upcast.  This is a syntactic transform only.
      if( DO_NOTNIL &&
          fun instanceof If &&
          args.at(0) instanceof Ident ) {
        Ident id = (Ident)args.at(0);
        args.set(1,new Apply(new Lambda(args.at(1), id._name),
                             new Apply(new NotNil(),new Ident(id._name))));
      }
      return new Apply(fun,args.asAry());
    }

    if( BUF[X]=='{' ) {         // Lambda of 1 or 2 args
      X++;                      // Skip paren
      Ary<String> args = new Ary<>(new String[1],0);
      while( skipWS()!='-' ) args.push(id());
      require();
      Syntax body = fterm();
      require('}');
      return new Lambda(body,args.asAry());
    }
    // Let or Id
    if( isAlpha0(BUF[X]) ) {
      String id = id();
      if( skipWS()!='=' ) {
        PrimSyn prim = PRIMSYNS.get(id); // No shadowing primitives or this lookup returns the prim instead of the shadow
        return prim==null ? new Ident(id) : prim.make(); // Make a prim copy with fresh HM variables
      }
      // Let expression; "id = term(); term..."
      X++;                      // Skip '='
      Syntax def = fterm();
      require(';');
      return new Let(id,def,fterm());
    }

    // Structure
    if( BUF[X]=='@' ) {
      X++;
      require('{');
      Ary<String>  ids = new Ary<>(String.class);
      Ary<Syntax> flds = new Ary<>(Syntax.class);
      while( skipWS()!='}' && X < BUF.length ) {
        String id = require('=',id());
        Syntax fld = fterm();
        if( fld==null ) throw unimpl("Missing term for field "+id);
        ids .push( id);
        flds.push(fld);
        if( skipWS()==',' ) X++;
      }
      require('}');
      return new Struct(ids.asAry(),flds.asAry());
    }

    throw unimpl("Unknown syntax");
  }
  // Parse a term with an optional following field.
  private static Syntax fterm() {
    Syntax term=term();
    while( true ) {
      if( term==null || skipWS()!='.' ) return term;
      X++;
      term = new Field(id(),term);
    }
  }
  private static final SB ID = new SB();
  private static String id() {
    ID.clear();
    while( X<BUF.length && isAlpha1(BUF[X]) )
      ID.p((char)BUF[X++]);
    String s = ID.toString().intern();
    if( s.length()==0 ) throw unimpl("Missing id");
    return s;
  }
  private static Syntax number() {
    if( BUF[X]=='0' ) { X++; return new Con(Type.NIL); }
    int sum=0;
    while( X<BUF.length && isDigit(BUF[X]) )
      sum = sum*10+BUF[X++]-'0';
    if( X>= BUF.length || BUF[X]!='.' )
      return new Con(TypeInt.con(sum));
    // Ambiguous '.' in: 2.3 vs 2.x (field load from a number)
    if( X+1<BUF.length && isAlpha0(BUF[X+1]) )
      return new Con(TypeInt.con(sum));
    X++;
    float f = (float)sum;
    f = f + (BUF[X++]-'0')/10.0f;
    return new Con(TypeFlt.con(f));
  }
  private static Syntax string() {
    int start = ++X;
    while( X<BUF.length && BUF[X]!='"' ) X++;
    return require('"', new Con(TypeMemPtr.make(BitsAlias.STRBITS,TypeStr.con(new String(BUF,start,X-start).intern()))));
  }
  private static byte skipWS() {
    while(true) {
      if( X == BUF.length ) return -1;
      if( X+1<BUF.length && BUF[X]=='/' && BUF[X+1]=='/' )
        while( BUF[X]!='\n' ) X++;
      if( !isWS(BUF[X]) ) return BUF[X];
      X++;
    }
  }
  private static boolean isWS    (byte c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
  private static boolean isDigit (byte c) { return '0' <= c && c <= '9'; }
  private static boolean isAlpha0(byte c) { return ('a'<=c && c <= 'z') || ('A'<=c && c <= 'Z') || (c=='_') || (c=='*') || (c=='?') || (c=='+'); }
  private static boolean isAlpha1(byte c) { return isAlpha0(c) || ('0'<=c && c <= '9') || (c=='/'); }
  private static void require(char c) { if( skipWS()!=c ) throw unimpl("Missing '"+c+"'"); X++; }
  private static <T> T require(char c, T t) { require(c); return t; }
  private static void require() {
    skipWS();
    if( X+2 >= BUF.length || BUF[X]!= '-' || BUF[X+1]!= '>' )
      throw unimpl("Missing '->'");
    X+=2;
  }

  // ---------------------------------------------------------------------
  // Worklist of Syntax nodes
  private static class Worklist {
    private final int _rseed;   // Randomize worklist draws
    public int _idx;            // Next item to get
    Worklist(int rseed) { _rseed=rseed; }
    private final Ary<Syntax> _ary = new Ary<>(Syntax.class); // For picking random element
    private final HashSet<Syntax> _work = new HashSet<>();    // For preventing dups
    public int len() { return _ary.len(); }
    public Syntax add(Syntax s) { if( s!=null && !_work.contains(s) ) _work.add(_ary.push(s)); return s; }
    public void add(Syntax[] ss) { if( ss!=null ) for( Syntax s : ss ) add(s); }
    public Syntax pop() {
      Syntax s = _ary.del( (_idx+=_rseed)%_ary._len );
      _work.remove(s);
      return s;
    }
    public boolean has(Syntax s) { return _work.contains(s); }
    public void addAll(Ary<? extends Syntax> ss) { if( ss != null ) for( Syntax s : ss ) add(s); }
    @Override public String toString() { return _ary.toString(); }
  }

  // ---------------------------------------------------------------------
  // Small classic tree of T2s, immutable, with sharing at the root parts.
  static class VStack implements Iterable<T2> {
    final VStack _par;
    private T2 _nongen;
    final int _d;
    VStack( VStack par, T2 nongen ) { _par=par; _nongen=nongen; _d = par==null ? 0 : par._d+1; }
    T2 nongen() { return _nongen; }
    @Override public String toString() {
      // Collect dups across the forest of types
      VBitSet dups = new VBitSet();
      for( VStack vs = this; vs!=null; vs = vs._par )
        vs._nongen._get_dups(new VBitSet(),dups);
      // Now recursively print
      return str(new SB(),dups).toString();
    }
    SB str(SB sb, VBitSet dups) {
      _nongen.str(sb,new VBitSet(),dups,true);
      if( _par!=null ) _par.str(sb.p(" , "),dups);
      return sb;
    }
    @NotNull @Override public Iterator<T2> iterator() { return new Iter(); }
    private class Iter implements Iterator<T2> {
      private VStack _vstk;
      Iter() { _vstk=VStack.this; }
      @Override public boolean hasNext() { return _vstk!=null; }
      @Override public T2 next() { T2 v = _vstk.nongen(); _vstk = _vstk._par;  return v; }
    }
  }

  // ---------------------------------------------------------------------
  static abstract class Syntax {
    Syntax _par;                // Parent in the AST
    VStack _nongen;             // Non-generative type variables
    T2 _hmt;                    // Current HM type

    // Dataflow types.  Varies during a run of GCP.
    Type _flow;

    // Compute and return (and do not set) a new GCP type for this syntax.
    abstract Type val(Worklist work);

    void add_val_work(Syntax child, @NotNull Worklist work) {} // Add affected neighbors to worklist

    // Visit whole tree recursively, applying 'map' to self, and reducing that
    // with the recursive value from all children.
    abstract <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce );

    // First pass to "prepare" the tree; does e.g. Ident lookup, sets initial
    // type-vars and counts tree size.
    abstract int prep_tree(Syntax par, VStack nongen, Worklist work);
    final void prep_tree_impl( Syntax par, VStack nongen, Worklist work, T2 t ) {
      _par = par;
      _hmt = t;
      _flow= Type.XSCALAR;
      _nongen = nongen;
      work.add(this);
    }
    void prep_lookup_deps(Ident id) {}

    // Giant Assert: True if OK; all Syntaxs off worklist do not make progress
    abstract boolean more_work(Worklist work);
    final boolean more_work_impl(Worklist work) {
      if( DO_TVARS && !work.has(this) )
        throw unimpl();
      if( DO_GCP ) {            // Doing GCP AND
        Type t = val(null);
        assert _flow.isa(t);    // Monotonic flow
        if( !work.has(this) && _flow!=t ) // Flow progress not on worklist
          return false;
      }
      return true;
    }
    // Print for debugger
    @Override final public String toString() { return str(new SB()).toString(); }
    abstract SB str(SB sb);
    // Line-by-line print with more detail
    public String p() { return p0(new SB(), new VBitSet()).toString(); }
    final SB p0(SB sb, VBitSet dups) {
      _hmt._get_dups(new VBitSet(),dups);
      VBitSet visit = new VBitSet();
      p1(sb.i(),dups);
      if( DO_TVARS  ) _hmt .str(sb.p(", HMT="), visit,dups,true);
      if( DO_GCP ) _flow.str(sb.p(", GCP="),visit.clr(),null,true);
      sb.nl();
      return p2(sb.ii(2),dups).di(2);
    }
    abstract SB p1(SB sb, VBitSet dups); // Self short print
    abstract SB p2(SB sb, VBitSet dups); // Recursion print
  }

  static class Con extends Syntax {
    final Type _con;
    Con(Type con) { super(); _con=con; }
    @Override SB str(SB sb) { return p1(sb,null); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(_con.toString()); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
    @Override Type val(Worklist work) { return _con; }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      // A '0' turns into a nilable leaf.
      T2 base = _con==Type.NIL ? T2.make_nil(T2.make_leaf()) : T2.make_base(_con);
      prep_tree_impl(par, nongen, work, base);
      return 1;
    }
    @Override boolean more_work(Worklist work) { return more_work_impl(work); }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) { return map.apply(this); }
  }


  static class Ident extends Syntax {
    private final String _name; // The identifier name
    private Syntax _def;        // Cached syntax defining point
    private int _idx;           // Index in Lambda (which arg of many)
    private T2 _idt;            // Cached type var for the name in scope
    private boolean _fresh;     // True if fresh-unify; short-cut for common case of an id inside its def vs in a Let body.
    Ident(String name) { _name=name; }
    @Override SB str(SB sb) { return p1(sb,null); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(_name); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
    @Override Type val(Worklist work) {
      return _def instanceof Let ? ((Let)_def)._def._flow : ((Lambda)_def)._types[_idx];
    }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      for( Syntax syn = _par; syn!=null; syn = syn._par )
        syn.prep_lookup_deps(this);

      // Lookup, and get the T2 type var and a pointer to the flow type.
      for( Syntax syn = _par; syn!=null; syn = syn._par ) {
        if( syn instanceof Lambda ) {
          Lambda lam = (Lambda)syn;
          if( (_idx = Util.find(lam._args,_name)) != -1 )
            return _init(lam,lam._targs[_idx],false);
        } else if( syn instanceof Let ) {
          Let let = (Let)syn;  _idx=-1;
          if( Util.eq(let._arg0,_name) )
            return _init(let,let._targ, !let._targ.nongen_in(nongen));
        }
      }
      throw new RuntimeException("Parse error, "+_name+" is undefined in "+_par);
    }
    private int _init(Syntax def,T2 idt, boolean fresh) {
      _def = def; _idt = idt; _fresh=fresh; return 1; }
    @Override boolean more_work(Worklist work) { return more_work_impl(work); }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) { return map.apply(this); }
  }


  static class Lambda extends Syntax {
    // Map from FIDXs to Lambdas
    static final NonBlockingHashMapLong<Lambda> FUNS = new NonBlockingHashMapLong<>();
    final String[] _args;                 // Lambda argument names
    final T2    [] _targs;                // HM argument types
    final Type  [] _types;                // Flow argument types
    final Ident[][] _refs;                // Identifiers referring to this argument
    final Syntax _body;                   // Lambda body
    final Ary<Apply> _applys;             // Applys using this Lambda
    final int _fidx;                      // Unique function idx
    static final String[] ARGNAMES = new String[]{" x"," y"," z"};

    Lambda(Syntax body, String... args) {
      _args=args;
      _body=body;
      // Type variables for all arguments
      _targs = new T2[args.length];
      for( int i=0; i<args.length; i++ ) _targs[i] = T2.make_leaf();
      // Flow types for all arguments
      _types = new Type[args.length];
      for( int i=0; i<args.length; i++ ) _types[i] = Type.XSCALAR;
      // Idents referring to this argument
      _refs = new Ident[args.length][];
      _applys = new Ary<>(Apply.class);
      // A unique FIDX for this Lambda
      _fidx = BitsFun.new_fidx();
      FUNS.put(_fidx,this);
      _flow = TypeFunPtr.makex(BitsFun.make0(_fidx),_args.length,Type.ANY,Type.XSCALAR);
    }
    @Override SB str(SB sb) {
      sb.p("{ ");
      for( String arg : _args ) sb.p(arg).p(' ');
      return _body.str(sb.p("-> ")).p(" }");
    }
    @Override SB p1(SB sb, VBitSet dups) {
      sb.p("{ ");
      for( int i=0; i<_args.length; i++ ) {
        sb.p(_args[i]);
        if( DO_TVARS  ) sb.p(", HMT=" ).p(_targs[i].toString());
        if( DO_GCP ) sb.p(", GCP=").p(_types[i]);
        sb.nl().i().p("  ");
      }
      return sb.p(" -> ... } ");
    }
    @Override SB p2(SB sb, VBitSet dups) { return _body.p0(sb,dups); }
    @Override Type val(Worklist work) {
      // Just wrap a function around the body return
      return TypeFunPtr.makex(BitsFun.make0(_fidx),_args.length,Type.ANY,_body._flow);
    }
    // Meet the formal argument# with a new Apply callsite actual arg.
    void arg_meet(int argn, Type cflow, Worklist work) {
      Type old = _types[argn];
      Type mt = old.meet(cflow);
      if( mt==old ) return;     // No change
      _types[argn]=mt;          // Yes change, update
      work.add(_refs[argn]);    // And revisit referrers
      if( this instanceof PrimSyn ) work.add(this); // Primitives recompute
    }


    // Ignore arguments, and return body type for a particular call site.  Very conservative.
    Type apply(Type[] flows) { return _body._flow; }
    @Override void add_val_work(Syntax child, Worklist work) {
      work.add(this);
      // Body changed, all Apply sites need to recompute
      work.addAll(_applys);
    }
    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      // Prep self
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      // Extend the nongen set by the new variables
      VStack vs = nongen;
      for( T2 targ : _targs ) vs = new VStack(vs, targ);
      // Prep the body
      int cnt = _body.prep_tree(this,vs,work) + 1;
      // Go ahead and pre-unify with a required function
      T2[] targs = Arrays.copyOf(_targs,_targs.length+1);
      targs[_targs.length] = _body._hmt;
      return cnt;
    }
    @Override void prep_lookup_deps(Ident id) {
      for( int i=0; i<_args.length; i++ )
        if( Util.eq(_args[i],id._name) ) {
          Ident[] refs = _refs[i];
          if( refs==null ) _refs[i] = refs = new Ident[0];
          // Hard linear-time append ident to the end.  Should be very limited in size.
          _refs[i] = refs = Arrays.copyOf(refs,refs.length+1);
          refs[refs.length-1] = id;
        }
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      return _body.more_work(work);
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez = map.apply(this);
      // Primitives have no body
      return _body==null ? rez : reduce.apply(rez,_body.visit(map,reduce));
    }
  }

  static class Let extends Syntax {
    final String _arg0;
    final Syntax _def, _body;
    T2 _targ;
    Syntax[] _refs;               // Identifiers referring here
    Let(String arg0, Syntax def, Syntax body) { _arg0=arg0; _body=body; _def=def; _targ=T2.make_leaf(); _refs=new Ident[0]; }
    @Override SB str(SB sb) { return _body.str(_def.str(sb.p(_arg0).p(" = ")).p("; ")); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(_arg0).p(" = ... ; ..."); }
    @Override SB p2(SB sb, VBitSet dups) { _def.p0(sb,dups); return _body.p0(sb,dups); }
    @Override Type val(Worklist work) { return _body._flow; }
    // Definition changed; all dependents need to revisit
    @Override void add_val_work( Syntax child, @NotNull Worklist work) {
      if( child==_def ) work.add(_refs);
      else              work.add(this);
    }

    @Override int prep_tree( Syntax par, VStack nongen, Worklist work ) {
      prep_tree_impl(par,nongen,work,_body._hmt);
      int cnt = _body.prep_tree(this,           nongen       ,work) +
                _def .prep_tree(this,new VStack(nongen,_targ),work);
      _hmt = _body._hmt;            // Unify 'Let._hmt' with the '_body'
      //_targ.unify(_def.find(),work);
      return cnt+1;
    }
    @Override void prep_lookup_deps(Ident id) {
      if( Util.eq(id._name,_arg0) ) {
        // Hard linear-time append ident to the end.  Should be very limited in size.
        _refs = Arrays.copyOf(_refs,_refs.length+1);
        _refs[_refs.length-1] = id;
      }
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      return _body.more_work(work) && _def.more_work(work);
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez  = map.apply(this);
      T def  = reduce.apply(rez,_def .visit(map,reduce));
      return   reduce.apply(def,_body.visit(map,reduce));
    }
  }


  static class Apply extends Syntax {
    final Syntax _fun;
    final Syntax[] _args;
    Apply(Syntax fun, Syntax... args) { _fun = fun; _args = args; }
    @Override SB str(SB sb) {
      _fun.str(sb.p("(")).p(" ");
      for( Syntax arg : _args )
        arg.str(sb).p(" ");
      return sb.unchar().p(")");
    }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p("(...)"); }
    @Override SB p2(SB sb, VBitSet dups) {
      _fun.p0(sb,dups);
      for( Syntax arg : _args ) arg.p0(sb,dups);
      return sb;
    }

    @Override Type val(Worklist work) {
      Type flow = _fun._flow;
      if( !(flow instanceof TypeFunPtr) ) return flow.oob(Type.SCALAR);
      TypeFunPtr tfp = (TypeFunPtr)flow;
      if( tfp._fidxs == BitsFun.EMPTY )
        return Type.XSCALAR; // Nothing being called, stay high
      if( work!=null )
        for( int fidx : tfp._fidxs ) {
          Lambda lambda = Lambda.FUNS.get(fidx);
          if( lambda._applys.find(this) == -1 ) {
            // new call site for lambda; all args must meet into this lambda;
            lambda._applys.push(this);
            for( int i=0; i<_args.length; i++ )
              lambda.arg_meet(i,_args[i]._flow,work);
            if( lambda instanceof PrimSyn ) // Normal lambdas update from their _body alone
              work.add(lambda);             // But primitives re-apply arguments
          }
      }
      return tfp._ret;
    }

    @Override void add_val_work( Syntax child, @NotNull Worklist work) {
      // push self, because self returns the changed-functions' ret
      if( child==_fun ) { work.add(this); return; }

      // Check for some Lambdas present
      Type flow = _fun._flow;
      if( !(flow instanceof TypeFunPtr) ) return;
      TypeFunPtr tfp = (TypeFunPtr)flow;
      if( tfp._fidxs == BitsFun.EMPTY ) return;

      // child arg to a call-site changed; find the arg#;
      int argn = Util.find(_args,child);

      // visit all Lambdas; meet the child flow into the Lambda arg#
      for( int fidx : tfp._fidxs )
        Lambda.FUNS.get(fidx).arg_meet(argn,child._flow,work);
    }

    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par,nongen,work,T2.make_leaf());
      int cnt = 1+_fun.prep_tree(this,nongen,work);
      for( Syntax arg : _args ) cnt += arg.prep_tree(this,nongen,work);
      return cnt;
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      if( !_fun.more_work(work) ) return false;
      for( Syntax arg : _args ) if( !arg.more_work(work) ) return false;
      return true;
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T slf = map.apply(this);
      T rez = reduce.apply(slf,_fun.visit(map,reduce));
      for( Syntax arg : _args )
        rez = reduce.apply(rez,arg.visit(map,reduce));
      return rez;
    }

  }


  static class Root extends Apply {
    static final Type[] FLOWS = new Type[0];
    Root(Syntax body) { super(body); }
    @Override SB str(SB sb) { return _fun.str(sb); }

    @Override Type val(Worklist work) {
      super.val(work);
      return _fun._flow;
    }
    // After GCP stability, we guess (badly) that all escaping functions
    // are called by folks outside of Root with the worst possible args.
    // TODO: Force programmer type annotations for module entry points.
    private static final VBitSet RVISIT = new VBitSet();
    void update_root_args(Worklist work) {
      // If an argument changes type, adjust the lambda arg types
      Type flow = _fun._flow;
      if( flow.above_center() ) return;
      RVISIT.clear();
      walk(flow,work);
    }
    private static void walk( Type flow, Worklist work) {
      if( RVISIT.tset(flow._uid) ) return;
        // Find any functions
      if( flow instanceof TypeFunPtr ) {
        if( ((TypeFunPtr)flow)._fidxs.test(1) ) return; // All of them
        // Meet the actuals over the formals.
        for( int fidx : ((TypeFunPtr)flow)._fidxs ) {
          Lambda fun = Lambda.FUNS.get(fidx);
          for( int i=0; i<fun._types.length; i++ )
            fun.arg_meet(i,Type.SCALAR,work);
          if( fun instanceof PrimSyn ) work.add(fun);
        }
      }

      // recursively walk structures for nested functions
      if( flow instanceof TypeMemPtr ) {
        TypeMemPtr tmp = (TypeMemPtr)flow;
        if( tmp._obj instanceof TypeStr ) return;
        TypeStruct ts = ((TypeStruct)tmp._obj);
        for( TypeFld fld : ts.flds() )
          walk(fld._t,work);
      }
    }


    // Expand functions to full signatures, recursively
    private static final VBitSet ADD_SIG = new VBitSet();
    Type flow_type() { ADD_SIG.clear(); return add_sig(_flow); }
    private static Type add_sig(Type t) {
      if( ADD_SIG.tset(t._uid) ) return t;
      if( t instanceof TypeFunPtr ) {
        TypeFunPtr fun = (TypeFunPtr)t;
        Type rez = Type.XSCALAR;
        if( fun._fidxs.test(1) ) rez = Type.SCALAR;
        else
          for( int fidx : fun._fidxs )
            rez = rez.meet(Lambda.FUNS.get(fidx).apply(FLOWS));
        Type rez2 = add_sig(rez);
        return TypeFunSig.make(TypeStruct.EMPTY,rez2);
      } else {
        return t;
      }
    }
  }


  // Structure or Records.
  static class Struct extends Syntax {
    final int _alias;
    final String[]  _ids;
    final Syntax[] _flds;
    Struct( String[] ids, Syntax[] flds ) {
      _ids=ids;
      _flds=flds;
      // Make a TMP
      _alias = BitsAlias.new_alias(BitsAlias.REC);
    }
    @Override SB str(SB sb) {
      sb.p("@{").p(_alias);
      for( int i=0; i<_ids.length; i++ ) {
        sb.p(' ').p(_ids[i]).p(" = ");
        _flds[i].str(sb);
        if( i < _ids.length-1 ) sb.p(',');
      }
      return sb.p("}");
    }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p("@{").p(_alias).p(" ... } "); }
    @Override SB p2(SB sb, VBitSet dups) {
      for( int i=0; i<_ids.length; i++ )
        _flds[i].p0(sb.i().p(_ids[i]).p(" = ").nl(),dups);
      return sb;
    }
    @Override Type val(Worklist work) {
      TypeFld[] flds = new TypeFld[_flds.length+1];
      flds[0] = TypeFld.NO_DISP;
      for( int i=0; i<_flds.length; i++ )
        flds[i+1] = TypeFld.make(_ids[i],_flds[i]._flow);
      TypeStruct tstr = TypeStruct.make(flds);
      TypeStruct t2 = tstr.approx(CUTOFF,BitsAlias.make0(_alias));
      return TypeMemPtr.make(_alias,t2);
    }
    @Override void add_val_work(Syntax child, Worklist work) { work.add(this); }

    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par, nongen, work, T2.make_struct(false,BitsAlias.make0(_alias),null,null));
      int cnt = 1;              // One for self
      T2[] t2s = new T2[_ids.length];
      if( _ids.length!=0 ) _hmt._args = new NonBlockingHashMap<>();
      for( int i=0; i<_ids.length; i++ ) { // Prep all sub-fields
        cnt += _flds[i].prep_tree(this,nongen,work);
        t2s[i] = _flds[i]._hmt;
        _hmt._args.put(_ids[i],t2s[i]);
      }
      return cnt;
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      for( Syntax fld : _flds )
        if( !fld.more_work(work) )
          return false;
      return true;
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez = map.apply(this);
      for( Syntax fld : _flds )
        rez = reduce.apply(rez,fld.visit(map,reduce));
      return rez;
    }
  }

  // Field lookup in a Struct
  static class Field extends Syntax {
    final String _id;
    final Syntax _rec;
    Field( String id, Syntax str ) { _id=id; _rec =str; }
    @Override SB str(SB sb) { return _rec.str(sb).p(".").p(_id); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(".").p(_id); }
    @Override SB p2(SB sb, VBitSet dups) { return _rec.p0(sb,dups); }
    @Override Type val(Worklist work) {
      Type trec = _rec._flow;
      if( trec.above_center() || trec==Type.NIL ) return Type.XSCALAR;
      if( trec instanceof TypeMemPtr ) {
        TypeMemPtr tmp = (TypeMemPtr)trec;
        if( tmp._obj instanceof TypeStruct ) {
          TypeStruct tstr = (TypeStruct)tmp._obj;
          TypeFld fld = tstr.get(_id);
          if( fld!=null ) return fld._t; // Field type
        }
        if( tmp._obj.above_center() ) return Type.XSCALAR;
      }
      return Type.SCALAR;
    }
    @Override void add_val_work(Syntax child, Worklist work) { work.add(this); } 
    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par, nongen, work, T2.make_leaf());
      return _rec.prep_tree(this,nongen,work)+1;
    }
    @Override boolean more_work(Worklist work) {
      if( !more_work_impl(work) ) return false;
      return _rec.more_work(work);
    }
    @Override <T> T visit( Function<Syntax,T> map, BiFunction<T,T,T> reduce ) {
      T rez = map.apply(this);
      return reduce.apply(rez,_rec.visit(map,reduce));
    }
  }


  abstract static class PrimSyn extends Lambda {
    static int PAIR_ALIAS, TRIPLE_ALIAS;
    static void reset() {
      PAIR_ALIAS   = BitsAlias.new_alias(BitsAlias.REC);
      TRIPLE_ALIAS = BitsAlias.new_alias(BitsAlias.REC);
    }
    static T2 BOOL (){ return T2.make_base(TypeInt.BOOL); }
    static T2 INT64(){ return T2.make_base(TypeInt.INT64); }
    static T2 STRP (){ return T2.make_base(TypeMemPtr.STRPTR); }
    static T2 FLT64(){ return T2.make_base(TypeFlt.FLT64); }
    abstract String name();
    private static final String[][] IDS = new String[][] {
      null,
      {"x"},
      {"x","y"},
      {"x","y","z"},
    };
    PrimSyn(T2 ...t2s) {
      super(null,IDS[t2s.length-1]);
      _hmt = T2.make_fun(BitsFun.make0(_fidx), t2s);
      for( int i=0; i<_targs.length; i++ )
        _targs[i] = _hmt.arg(Lambda.ARGNAMES[i]);
    }
    abstract PrimSyn make();
    @Override int prep_tree(Syntax par, VStack nongen, Worklist work) {
      prep_tree_impl(par,nongen,work, _hmt);
      return 1;
    }
    @Override Type val(Worklist work) {
      assert _body==null;
      Type ret = apply(_types);
      return TypeFunPtr.makex(BitsFun.make0(_fidx),_args.length,Type.ANY,ret);
    }

    @Override boolean more_work(Worklist work) { return more_work_impl(work); }
    @Override SB str(SB sb){ return sb.p(name()); }
    @Override SB p1(SB sb, VBitSet dups) { return sb.p(name()); }
    @Override SB p2(SB sb, VBitSet dups) { return sb; }
  }


  // Pair
  static class Pair extends PrimSyn {
    @Override String name() { return "pair"; }
    static private T2 var1,var2;
    public Pair() {
      super(var1=T2.make_leaf(),var2=T2.make_leaf(),T2.make_struct(false,BitsAlias.make0(PAIR_ALIAS),new String[]{"0","1"},new T2[]{var1,var2}));
    }
    @Override PrimSyn make() { return new Pair(); }
    @Override Type apply(Type[] flows) {
      TypeFld[] ts = new TypeFld[flows.length+1];
      ts[0] = TypeFld.NO_DISP;  // Display
      for( int i=0; i<flows.length; i++ ) ts[i+1] = TypeFld.make_tup(flows[i],ARG_IDX+i);
      TypeStruct tstr = TypeStruct.make(ts);
      TypeStruct ts2 = tstr.approx(CUTOFF,BitsAlias.make0(PAIR_ALIAS));
      return TypeMemPtr.make(PAIR_ALIAS,ts2);
    }
  }


  // Triple
  static class Triple extends PrimSyn {
    @Override String name() { return "triple"; }
    static private T2 var1,var2,var3;
    public Triple() { super(var1=T2.make_leaf(),var2=T2.make_leaf(),var3=T2.make_leaf(),T2.make_struct(false,BitsAlias.make0(TRIPLE_ALIAS),new String[]{"0","1","2"},new T2[]{var1,var2,var3})); }
    @Override PrimSyn make() { return new Triple(); }
    @Override Type apply(Type[] flows) {
      TypeFld[] ts = new TypeFld[flows.length+1];
      ts[0] = TypeFld.NO_DISP;  // Display
      for( int i=0; i<flows.length; i++ ) ts[i+1] = TypeFld.make_tup(flows[i],ARG_IDX+i);
      TypeStruct tstr = TypeStruct.make(ts);
      TypeStruct ts2 = tstr.approx(CUTOFF,BitsAlias.make0(TRIPLE_ALIAS));
      return TypeMemPtr.make(TRIPLE_ALIAS,ts2);
    }
  }

  // Special form of a Lambda body for IF which changes the H-M rules.
  // None-executing paths do not unify args.
  static class If extends PrimSyn {
    @Override String name() { return "if"; }
    public If() { super(T2.make_leaf(),T2.make_leaf(),T2.make_leaf(),T2.make_leaf()); }
    @Override PrimSyn make() { return new If(); }
    @Override Type apply( Type[] flows) {
      Type pred= flows[0];
      Type t1  = flows[1];
      Type t2  = flows[2];
      // Conditional Constant Propagation: only prop types from executable sides
      if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
        return t2;              // False only
      if( pred.above_center() ) // Delay any values
        return Type.XSCALAR;    // t1.join(t2);     // Join of either
      if( !pred.must_nil() )    // True only
        return t1;
      // Could be either, so meet
      return t1.meet(t2);
    }
  }

  // EQ
  static class EQ extends PrimSyn {
    @Override String name() { return "eq"; }
    static private T2 var1;
    public EQ() { super(var1=T2.make_leaf(),var1,BOOL()); }
    @Override PrimSyn make() { return new EQ(); }
    @Override Type apply( Type[] flows) {
      Type x0 = flows[0];
      Type x1 = flows[1];
      if( x0.above_center() || x1.above_center() ) return TypeInt.BOOL.dual();
      if( x0.is_con() && x1.is_con() && x0==x1 )
        return TypeInt.TRUE;
      // TODO: Can also know about nil/not-nil
      return TypeInt.BOOL;
    }
  }

  // EQ0
  static class EQ0 extends PrimSyn {
    @Override String name() { return "eq0"; }
    public EQ0() { super(INT64(),BOOL()); }
    @Override PrimSyn make() { return new EQ0(); }
    @Override Type apply( Type[] flows) {
      Type pred = flows[0];
      if( pred.above_center() )
        return pred.may_nil() ? TypeInt.BOOL.dual() : TypeInt.FALSE;
      if( pred==Type.ALL ) return TypeInt.BOOL;
      if( pred == TypeInt.FALSE || pred == Type.NIL || pred==Type.XNIL )
        return TypeInt.TRUE;
      if( pred.meet(Type.NIL)!=pred )
        return TypeInt.FALSE;
      return TypeInt.BOOL;
    }
  }

  static class IsEmpty extends PrimSyn {
    @Override String name() { return "isempty"; }
    public IsEmpty() { super(STRP(),BOOL()); }
    @Override PrimSyn make() { return new IsEmpty(); }
    @Override Type apply( Type[] flows) {
      Type pred = flows[0];
      if( pred.above_center() ) return TypeInt.BOOL.dual();
      TypeObj to;
      if( pred instanceof TypeMemPtr && (to=((TypeMemPtr)pred)._obj) instanceof TypeStr && to.is_con() )
        return TypeInt.con(to.getstr().isEmpty() ? 1 : 0);
      return TypeInt.BOOL;
    }
  }

  // Remove a nil from a struct after a guarding if-test
  static class NotNil extends PrimSyn {
    @Override String name() { return " notnil"; }
    public NotNil() { super(T2.make_leaf(),T2.make_leaf()); }
    @Override PrimSyn make() { throw unimpl(); /*return new NotNil(); */}
    @Override Type apply( Type[] flows) {
      Type val = flows[0];
      if( val==Type.XNIL ) return Type.XSCALAR; // Weird case of not-nil nil
      return val.join(Type.NSCALR);
    }
  }

  // multiply
  static class Mul extends PrimSyn {
    @Override String name() { return "*"; }
    public Mul() { super(INT64(),INT64(),INT64()); }
    @Override PrimSyn make() { return new Mul(); }
    @Override Type apply( Type[] flows) {
      Type t0 = flows[0];
      Type t1 = flows[1];
      if( t0.above_center() || t1.above_center() )
        return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t1 instanceof TypeInt ) {
        if( t0.is_con() && t0.getl()==0 ) return TypeInt.ZERO;
        if( t1.is_con() && t1.getl()==0 ) return TypeInt.ZERO;
        if( t0.is_con() && t1.is_con() )
          return TypeInt.con(t0.getl()*t1.getl());
      }
      return TypeInt.INT64;
    }
  }

  // add integers
  static class Add extends PrimSyn {
    @Override String name() { return "+"; }
    public Add() { super(INT64(),INT64(),INT64()); }
    @Override PrimSyn make() { return new Add(); }
    @Override Type apply( Type[] flows) {
      Type t0 = flows[0];
      Type t1 = flows[1];
      if( t0.above_center() || t1.above_center() )
        return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t1 instanceof TypeInt ) {
        if( t0.is_con() && t1.is_con() )
          return TypeInt.con(t0.getl()+t1.getl());
      }
      return TypeInt.INT64;
    }
  }

  // decrement
  static class Dec extends PrimSyn {
    @Override String name() { return "dec"; }
    public Dec() { super(INT64(),INT64()); }
    @Override PrimSyn make() { return new Dec(); }
    @Override Type apply( Type[] flows) {
      Type t0 = flows[0];
      if( t0.above_center() ) return TypeInt.INT64.dual();
      if( t0 instanceof TypeInt && t0.is_con() )
        return TypeInt.con(t0.getl()-1);
      return TypeInt.INT64;
    }
  }

  // int->str
  static class Str extends PrimSyn {
    @Override String name() { return "str"; }
    public Str() { super(INT64(),STRP()); }
    @Override PrimSyn make() { return new Str(); }
    @Override Type apply( Type[] flows) {
      Type i = flows[0];
      if( i.above_center() ) return TypeMemPtr.STRPTR.dual();
      if( i instanceof TypeInt && i.is_con() )
        return TypeMemPtr.make(BitsAlias.STRBITS,TypeStr.con(String.valueOf(i.getl()).intern()));
      return TypeMemPtr.STRPTR;
    }
  }


  // flt->(factor flt flt)
  static class Factor extends PrimSyn {
    @Override String name() { return "factor"; }
    public Factor() { super(FLT64(),FLT64()); }
    @Override PrimSyn make() { return new Factor(); }
    @Override Type apply( Type[] flows) {
      Type flt = flows[0];
      if( flt.above_center() ) return TypeFlt.FLT64.dual();
      return TypeFlt.FLT64;
    }
  }

  // ---------------------------------------------------------------------
  // T2 types form a Lattice, with 'unify' same as 'meet'.  T2's form a DAG
  // (cycles if i allow recursive unification) with sharing.  Each Syntax has a
  // T2, and the forest of T2s can share.  Leaves of a T2 can be either a
  // simple concrete base type, or a sharable leaf.  Unify is structural, and
  // where not unifyable the union is replaced with an Error.
  static class T2 implements IntSupplier {
    private static int CNT=1;
    final int _uid=CNT++;
    @Override public int getAsInt() { return _uid; }

    // Structural parts to unify with, or null.
    // If Leaf   , then null and _flow is null.
    // If Base   , then null and _flow is set.
    // If unified, contains the single key ">>" and all other fields are null.
    // If Nil    , contains the single key "?"  and all other fields are null.
    // If Lambda , contains keys "x","y","z" for args or "ret" for return.
    // If Struct , contains keys for the field labels.  No display & not-null.
    NonBlockingHashMap<String,T2> _args;

    // Any/all of Base,Lambda,Struct may appear at once.
    // If more than one appears, then we have a "Cannot unify" error.
    // Nil is NOT allowed to appear with others, but it can fold into all of them.

    // Contains a Bases flow-type, or null if not a Base.
    Type _flow;
    Type _eflow;                // Error flow; incompatible with _flow

    // Contains the set of Lambdas, or null if not a Lambda.
    // If set, then keys x,y,z,ret may appear.
    BitsFun _fidxs;

    // Contains the set of aliased Structs, or null if not a Struct.
    // If set, then keys for field names may appear.
    BitsAlias _aliases;
    // Structs allow more fields.  Not quite the same as TypeStruct._open field.
    boolean _open;

    // Null for no-error, or else a single-T2 error
    String _err = null;

    private T2(NonBlockingHashMap<String,T2> args) { _args = args; }

    @SuppressWarnings("unchecked")
    T2 copy() {
      // Shallow clone of args
      T2 t = new T2(_args==null ? null : (NonBlockingHashMap<String,T2>)_args.clone());
      t._flow = _flow;
      t._eflow = _eflow;
      t._fidxs = _fidxs;
      t._aliases = _aliases;
      t._open = _open;
      t._err = _err;
      return t;
    }

    boolean is_leaf()  { return _args==null && _flow==null && _aliases==null; }
    boolean is_nil()   { return arg("?" )!=null; }
    boolean is_base()  { return _flow   != null; } // Can be true with is_base, is_fun, is_struct
    boolean is_fun ()  { return _fidxs  != null; } // Can be true with is_base, is_fun, is_struct
    boolean is_struct(){ return _aliases!= null; } // Can be true with is_base, is_fun, is_struct
    boolean is_open()  { return _open; }           // Struct-specific
    boolean is_err()   { return _err!=null || is_err2(); }
    boolean is_err2()  { return
        (_flow   ==null ? 0 : 1) +                 // Any 2 or more set of _flow,_fidxs,_aliases
        (_eflow  ==null ? 0 : 1) +                 // Any 2 or more set of _flow,_fidxs,_aliases
        (_fidxs  ==null ? 0 : 1) +
        (_aliases==null ? 0 : 1) >= 2;
    }
    int size() { return _args==null ? 0 : _args.size(); }
    // Arg lookup
    T2 arg( String key) { return _args.get(key); }

    // Constructor factories.
    static T2 make_leaf() { return new T2(null); }
    static T2 make_nil (T2 leaf) { return new T2(new NonBlockingHashMap<>(){{put("?",leaf);}}); }
    static T2 make_base(Type flow) {
      assert !(flow instanceof TypeStruct) && !(flow instanceof TypeFunPtr);
      T2 t2 = new T2(null);
      t2._flow=flow.widen(); // TODO: needed?  removes HM narrow base types
      return t2;
    }
    static T2 make_fun( BitsFun fidxs, T2... t2s ) {
      NonBlockingHashMap<String,T2> args = new NonBlockingHashMap<>();
      for( int i=0; i<t2s.length-1; i++ )
        args.put(Lambda.ARGNAMES[i], t2s[i]);
      args.put("ret",t2s[t2s.length-1]);
      T2 t2 = new T2(args);
      t2._fidxs = fidxs;
      return t2;
    }
    // A struct with fields
    static T2 make_struct( boolean open, BitsAlias aliases, String[] ids, T2[] flds ) {
      NonBlockingHashMap<String,T2> args = ids==null ? null : new NonBlockingHashMap<>();
      if( ids!=null )
        for( int i=0; i<ids.length; i++ )
          args.put(ids[i],flds[i]);
      T2 t2 = new T2(args);
      t2._aliases = aliases;
      t2._open = open;
      return t2;
    }

    // -----------------
    private static final VBitSet ODUPS = new VBitSet();

    boolean _occurs_in_type(T2 x) {
      if( x==this ) return true;
      if( ODUPS.tset(x._uid) ) return false; // Been there, done that
      if( x._args!=null )
        for( String key : x._args.keySet() )
          if( _occurs_in_type(x.arg(key)) )
            return true;
      return false;
    }

    boolean nongen_in(VStack vs) {
      if( vs==null ) return false;
      ODUPS.clear();
      for( T2 t2 : vs )
        if( _occurs_in_type(t2) )
          return true;
      return false;
    }

    // -----------------
    // Glorious Printing

    // Look for dups, in a tree or even a forest (which Syntax.p() does)
    public VBitSet get_dups() { return _get_dups(new VBitSet(),new VBitSet()); }
    public VBitSet _get_dups(VBitSet visit, VBitSet dups) {
      if( visit.tset(_uid) ) {
        dups.set(_uid);
      } else {
        if( _args!=null )
          for( T2 t : _args.values() )
            t._get_dups(visit,dups);
      }
      return dups;
    }

    @Override public String toString() { return str(new SB(), new VBitSet(), get_dups(), true ).toString(); }
    public String p() { VCNT=0; VNAMES.clear(); return str(new SB(), new VBitSet(), get_dups(), false ).toString(); }
    private static int VCNT;
    private static final HashMap<T2,String> VNAMES = new HashMap<>();


    // Fancy print for Debuggers - includes explicit U-F re-direction.
    // Does NOT roll-up U-F, has no side-effects.
    SB str(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      boolean dup = dups.get(_uid);
      if( !debug ) return str(sb,visit,dups,debug);
      if( is_leaf() && _err==null ) {
        vname(sb,debug);
        return sb;
      }

      // Dup printing for all but bases (which are short, just repeat them)
      if( debug || !is_base() || is_err() ) {
        if( dup ) vname(sb,debug);
        if( visit.tset(_uid) && dup ) return sb;
        if( dup ) sb.p(':');
      }

      // Special printing for errors
      if( is_err() ) {
        if( is_err2() ) {
          sb.p("Cannot unify ");
          if( is_fun   () ) str_fun   (sb,visit,dups,debug).p(" and ");
          if( is_base  () ) str_base  (sb,visit,dups,debug).p(" and ");
          if( _eflow!=null) sb.p(_eflow)                   .p(" and ");
          if( is_struct() ) str_struct(sb,visit,dups,debug).p(" and ");
          return sb.unchar(5);
        }
        return sb.p(_err);      // Just a simple error
      }

      if( is_base() )
        return str_base(sb,visit,dups,debug);

      // Special printing for functions
      if( is_fun() )
        return str_fun(sb,visit,dups,debug);

      // Special printing for structures
      if( is_struct() )
        return str_struct(sb,visit,dups,debug);

      if( is_nil() )
        return str0(sb,visit,arg("?"),dups,debug).p('?');

      // Generic structural T2
      sb.p("( ");
      if( _args!=null )
        for( String s : _args.keySet() )
          str0(sb.p(s).p(':'),visit,_args.get(s),dups,debug).p(" ");
      return sb.unchar().p(")");
    }
    static private SB str0(SB sb, VBitSet visit, T2 t, VBitSet dups, boolean debug) { return t==null ? sb.p("_") : t.str(sb,visit,dups,debug); }
    private SB str_base(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      return sb.p(_flow);
    }
    private SB str_fun(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      if( debug ) _fidxs.clear(0).str(sb);
      sb.p("{ ");
      for( String fld : sorted_flds() ) {
        if( fld.charAt(0)!=' ' ) continue; // Ignore struct field
        if( !Util.eq("ret",fld) )
          str0(sb,visit,_args.get(fld),dups,debug).p(' ');
      }
      return str0(sb.p("-> "),visit,_args.get("ret"),dups,debug).p(" }");
    }
    private SB str_struct(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
      if( is_prim() ) return sb.p("@{PRIMS}");
      final boolean is_tup = is_tup(); // Distinguish tuple from struct during printing
      if( debug )
        _aliases.clear(0).str(sb);
      sb.p(is_tup ? "(" : "@{");
      if( _args==null ) sb.p(" ");
      else {
        for( String fld : sorted_flds() ) {
          // Skip fields from functions
          if( fld.charAt(0)==' ' ) continue;
          if( Util.eq(fld,"ret") ) continue;
          // Skip field names in a tuple
          str0(is_tup ? sb.p(' ') : sb.p(' ').p(fld).p(" = "),visit,_args.get(fld),dups,debug).p(is_tup ? ',' : ';');
        }
      }
      if( is_open() ) sb.p(" ...,");
      if( _args!=null && _args.size() > 0 ) sb.unchar();
      sb.p(!is_tup ? "}" : ")");
      if( _aliases.test(0) ) sb.p("?");
      return sb;
    }


    private void vname( SB sb, boolean debug) {
      final boolean vuid = debug && is_leaf();
      sb.p(VNAMES.computeIfAbsent(this, (k -> vuid ? ((is_leaf() ? "V" : "X") + k._uid) : ((++VCNT) - 1 + 'A' < 'V' ? ("" + (char) ('A' + VCNT - 1)) : ("V" + VCNT)))));
    }
    private boolean is_tup() { return _args==null || _args.isEmpty() || _args.containsKey("0"); }
    private Collection<String> sorted_flds() { return new TreeMap<>(_args).keySet(); }
    boolean is_prim() {
      return is_struct() && _args!=null && _args.containsKey("!");
    }

    // Debugging tool
    T2 find(int uid) { return _find(uid,new VBitSet()); }
    private T2 _find(int uid, VBitSet visit) {
      if( visit.tset(_uid) ) return null;
      if( _uid==uid ) return this;
      if( _args==null ) return null;
      for( T2 arg : _args.values() )
        if( (arg=arg._find(uid,visit)) != null )
          return arg;
      return null;
    }
    static void reset() { CNT=0; ODUPS.clear(); }
  }
}
