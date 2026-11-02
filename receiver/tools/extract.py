import re,sys,os
S=os.environ["S"]; L=S+'/src/UxPlay/lib/'
def strip_comments(t):
    t=re.sub(r'/\*.*?\*/','',t,flags=re.S)
    return re.sub(r'//[^\n]*','',t)
out={}
def parse_file(path, wanted=None):
    t=strip_comments(open(path).read())
    # find declarations
    for m in re.finditer(r'(unsigned\s+char|unsigned\s+long|uint32_t|char)\s+(\w+)\s*((?:\[[^\]]*\]\s*)+)=\s*\{',t):
        typ,name=m.group(1),m.group(2)
        i=m.end()-1; depth=0; j=i
        while True:
            c=t[j]
            if c=='{': depth+=1
            elif c=='}':
                depth-=1
                if depth==0: break
            j+=1
        body=t[i:j+1]
        nums=re.findall(r'-?0x[0-9a-fA-F]+|-?\d+',body)
        vals=[int(n,0) for n in nums]
        is32 = typ in ('unsigned long','uint32_t')
        key=name
        if name in out and name.startswith('buffer'): continue
        out[key]=(is32,vals)
for f in ['playfair/omg_hax.c','playfair/omg_hax.h','playfair/sap_hash.c','fairplay_playfair.c']:
    parse_file(L+f)
want=['message_key','message_iv','z_key','x_key','t_key','table_s1','table_s2','table_s3','table_s4','table_s5','table_s6','table_s7','table_s8','table_s9','table_s10','sap_iv','sap_key_material','index_mangle','initial_session_key','static_source_1','static_source_2','default_sap','buffer0','buffer2','buffer4','reply_message','fp_header']
for w in want:
    assert w in out,w
    print(w,out[w][0],len(out[w][1]),file=sys.stderr)
def hexs(is32,vals):
    if is32: return ''.join('%08x'%(v&0xffffffff) for v in vals)
    return ''.join('%02x'%(v&0xff) for v in vals)
k=[]
k.append('// GENERADO a partir de UxPlay/RPiPlay (playfair, GPL-3.0). Ver NOTICE. No editar a mano.\npackage live.hogar.receiver.fairplay\n\n')
k.append('internal object FairPlayTables {\n    private val DATA: Map<String, Array<String>> = mapOf(\n')
for w in want:
    is32,vals=out[w]; h=hexs(is32,vals)
    chunks=[h[i:i+16000] for i in range(0,len(h),16000)] or ['']
    k.append('        "%s" to arrayOf(%s),\n'%(w+('#32' if is32 else ''),', '.join('"%s"'%c for c in chunks)))
k.append('    )\n\n')
k.append('''    private fun hex(name: String): String = DATA[name]!!.joinToString("")

    fun bytes(name: String): ByteArray {
        val h = hex(name)
        return ByteArray(h.length / 2) { ((Character.digit(h[it * 2], 16) shl 4) or Character.digit(h[it * 2 + 1], 16)).toByte() }
    }

    fun ints(name: String): IntArray {
        val h = hex("$name#32")
        return IntArray(h.length / 8) { java.lang.Long.parseLong(h.substring(it * 8, it * 8 + 8), 16).toInt() }
    }
}
''')
os.makedirs(sys.argv[1],exist_ok=True)
open(sys.argv[1]+'/FairPlayTables.kt','w').write(''.join(k))
