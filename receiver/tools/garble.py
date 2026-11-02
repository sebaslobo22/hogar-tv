import re,sys,os
S=os.environ["S"]
t=open(S+'/src/UxPlay/lib/playfair/hand_garble.c').read()
t=re.sub(r'/\*.*?\*/','',t,flags=re.S)
t=re.sub(r'//[^\n]*','',t)
i=t.index('void garble(')
i=t.index('{',i)
body=t[i+1:t.rindex('}')]
# remove printf lines
body=re.sub(r'printf\([^;]*\);','',body)
# split on ; at depth 0
stmts=[];cur='';d=0
for c in body:
    if c in '([': d+=1
    if c in ')]': d-=1
    if c==';' and d==0:
        stmts.append(cur.strip());cur=''
    else: cur+=c
stmts=[s for s in stmts if s]
tok_re=re.compile(r'\s*(0x[0-9a-fA-F]+|\d+|[A-Za-z_]\w*|<<|>>|[-+*/%&|^~(),\[\]])')
def tokenize(s):
    pos=0;out=[]
    s=s.strip()
    while pos<len(s):
        m=tok_re.match(s,pos)
        if not m: raise Exception('tok '+s[pos:pos+20])
        out.append(m.group(1));pos=m.end()
    return out
class P:
    def __init__(s,toks): s.t=toks;s.i=0
    def peek(s): return s.t[s.i] if s.i<len(s.t) else None
    def next(s): v=s.t[s.i];s.i+=1;return v
    def binary(s,ops,sub,emit):
        l=sub()
        while s.peek() in ops:
            op=s.next();r=sub();l=emit(op,l,r)
        return l
    def expr(s): return s.bor()
    def bor(s): return s.binary(('|',),s.bxor,lambda o,l,r:'(%s or %s)'%(l,r))
    def bxor(s): return s.binary(('^',),s.band,lambda o,l,r:'(%s xor %s)'%(l,r))
    def band(s): return s.binary(('&',),s.shift,lambda o,l,r:'(%s and %s)'%(l,r))
    def shift(s): return s.binary(('<<','>>'),s.add,lambda o,l,r:'(%s %s %s)'%(l,'shl' if o=='<<' else 'ushr',r))
    def add(s): return s.binary(('+','-'),s.mul,lambda o,l,r:'(%s %s %s)'%(l,o,r))
    def mul(s):
        def em(o,l,r):
            if o=='*': return '(%s * %s)'%(l,r)
            if o=='/': return 'udiv(%s, %s)'%(l,r)
            return 'umod(%s, %s)'%(l,r)
        return s.binary(('*','/','%'),s.unary,em)
    def unary(s):
        p=s.peek()
        if p=='~': s.next();return '(%s).inv()'%s.unary()
        if p=='-': s.next();return '(-%s)'%s.unary()
        return s.primary()
    def primary(s):
        p=s.next()
        if p=='(':
            e=s.expr();assert s.next()==')';return '('+e+')'
        if re.match(r'0x|\d',p):
            v=int(p,0)
            return str(v) if v<=0x7fffffff else '0x%x.toInt()'%v
        # ident
        if s.peek()=='[':
            s.next();e=s.expr();assert s.next()==']'
            m=re.match(r'buffer(\d)$',p);assert m
            return 'b%s[%s]'%(m.group(1),e)
        if s.peek()=='(':
            s.next();args=[]
            while s.peek()!=')':
                args.append(s.expr())
                if s.peek()==',':s.next()
            s.next()
            return '%s(%s)'%({'weird_ror8':'weirdRor8','weird_rol8':'weirdRol8','weird_rol32':'weirdRol32','rol8x':'rol8x','rol8':'rol8'}[p],', '.join(args))
        return p
lines=[]
for st in stmts:
    if st.startswith('unsigned int'): continue
    # lhs: ident or ident[ ... ]
    # find assignment operator: scan tokens at depth 0 for '=' ; tokenizer lacks '=' so handle string-level
    m=re.match(r'^\s*(\w+(?:\[.*?\])?)\s*(\+|-|\^|&|\||)=\s*(.*)$',st,flags=re.S)
    # lhs index may contain nested [] -> do manual
    # manual parse
    j=0;d=0
    k=None
    for idx,c in enumerate(st):
        if c in '([':d+=1
        elif c in ')]':d-=1
        elif c=='=' and d==0: k=idx;break
    lhs=st[:k].strip();rest=st[k+1:]
    op=''
    if lhs and lhs[-1] in '+-^&|' and not lhs.endswith(']'):
        op=lhs[-1];lhs=lhs[:-1].strip()
    lp=P(tokenize(lhs));l=lp.primary();assert lp.peek() is None,st
    rp=P(tokenize(rest));r=rp.expr();assert rp.peek() is None,st
    op_map={'+':'+','-':'-','^':'xor','&':'and','|':'or'}
    if op=='': lines.append('    %s = %s'%(l,r))
    else: lines.append('    %s = %s'%(l,'(%s %s %s)'%(l,op_map[op],r) ))
hdr='''// GENERADO por transpilación de UxPlay playfair/hand_garble.c (GPL-3.0). Ver NOTICE. No editar a mano.
package live.hogar.receiver.fairplay

internal class U8(val a: ByteArray) {
    operator fun get(i: Int): Int = a[i].toInt() and 0xFF
    operator fun set(i: Int, v: Int) { a[i] = v.toByte() }
}

private fun udiv(a: Int, b: Int): Int = Integer.divideUnsigned(a, b)
private fun umod(a: Int, b: Int): Int = Integer.remainderUnsigned(a, b)

internal fun rol8(input: Int, count: Int): Int {
    val x = input and 0xFF
    return ((x shl count) and 0xFF) or (x ushr (8 - count))
}

internal fun rol8x(input: Int, count: Int): Int {
    val x = input and 0xFF
    return (x shl count) or (x ushr (8 - count))
}

private fun weirdRor8(input: Int, count: Int): Int {
    if (count == 0) return 0
    val x = input and 0xFF
    return (x ushr count) or (x shl (8 - count))
}

private fun weirdRol8(input: Int, count: Int): Int {
    if (count == 0) return 0
    val x = input and 0xFF
    return ((x shl count) and 0xFF) or (x ushr (8 - count))
}

private fun weirdRol32(input: Int, count: Int): Int {
    if (count == 0) return 0
    val x = input and 0xFF
    return (x shl count) xor (x ushr (8 - count))
}

internal fun garble(buffer0: ByteArray, buffer1: ByteArray, buffer2: ByteArray, buffer3: ByteArray, buffer4: ByteArray) {
    val b0 = U8(buffer0); val b1 = U8(buffer1); val b2 = U8(buffer2); val b3 = U8(buffer3); val b4 = U8(buffer4)
    var tmp = 0; var tmp2 = 0; var tmp3 = 0
    var A = 0; var B = 0; var C = 0; var D = 0; var E = 0; var M = 0; var J = 0; var G = 0; var F = 0; var H = 0
    var K = 0; var R = 0; var S = 0; var T = 0; var U = 0; var V = 0; var W = 0; var X = 0; var Y = 0; var Z = 0
'''
open(sys.argv[1]+'/Garble.kt','w').write(hdr+'\n'.join(lines)+'\n}\n')
print(len(lines),'statements')
