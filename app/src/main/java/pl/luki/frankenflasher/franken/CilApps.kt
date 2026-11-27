package pl.luki.frankenflasher.franken

/**
 * Reguly SELinux vendora dla domen aplikacji dawcy AOSP, ktorych vendor bazy nie zna.
 *
 * Przyklad: DerpFest (OnePlus 15R) ma aparat OnePlus (com.oplus.camera) w domenie opluscamera_app.
 * Typ jest zadeklarowany w system_ext dawcy, ale app_domain()/net_domain() i dostep do sprzetu aparatu
 * sa w polityce VENDORA dawcy. Z vendorem ColorOS bazy tych regul nie ma: brak przejscia tmpfs ->
 * appdomain_tmpfs i userfaultfd -> ART: "Could not create private region after zygote fork:
 * Failed to initialize memory file: Permission denied" -> aplikacja pada przy starcie.
 *
 * Zrodla regul (dopisywanych na koniec vendor_sepolicy.cil bazy):
 *  1. podstawowe: to, co robi app_domain()/net_domain() (tmpfs, userfaultfd, siec)
 *  2. vendor dawcy: reguly z ta domena, nazwy bez wersji dawcy (X_202604 -> X)
 *  3. vendor bazy: reguly domeny, w ktorej baza uruchamia ten pakiet (seapp_contexts vendora/odm),
 *     a gdy jej nie ma i pakiet ma seinfo=platform - reguly platform_app (dostep do HAL-i ColorOS)
 * Zostaja tylko reguly, ktorych wszystkie nazwy, klasy i uprawnienia istnieja w polaczonej polityce.
 */
internal object CilApps {

    class Result(
        val domains: Map<String, List<String>>,   // domena -> pakiety
        val rules: List<String>,
        val essential: Set<String>,
        val fromDonor: Int,
        val fromBase: Map<String, Int>,           // "domena<-wzor" -> liczba
        val rejected: Int,
        val translated: Map<String, String>,      // typ dawcy -> typ bazy (ta sama sciezka/wlasciwosc/usluga)
    )

    /**
     * Konteksty jednej strony (dawcy albo bazy po polaczeniu): teksty *_file_contexts, *_property_contexts,
     * *_service_contexts, *_hwservice_contexts sklejone w kolejnosci ladowania + genfscon z CIL.
     */
    class Ctx(val file: String = "", val property: String = "", val service: String = "", val hwservice: String = "")

    private val GENFS = Regex("""\(genfscon\s+([^\s()]+)\s+"?([^\s()"]+)"?\s+(?:[^\s()]+\s+)?\(\s*[^\s()]+\s+[^\s()]+\s+([^\s()]+)""")
    private val RE_META = "()[]{}*+?|^$"

    private fun ctxLines(t: String) = t.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .map { it.split(Regex("""\s+""")) }
    private fun ctxType(c: String?) = c?.split(':')?.getOrNull(2)

    /**
     * Typy dawcy, ktorych baza nie zna -> typ, ktorym baza oznacza TO SAMO miejsce:
     *  genfscon (najdluzszy pasujacy prefiks sciezki, jak jadro), file_contexts (ten sam wzorzec albo
     *  wzorzec bazy pasujacy do sciezki dawcy), property_contexts (ta sama nazwa / najdluzszy prefiks),
     *  service/hwservice_contexts (ta sama nazwa). Np. proc_ufs_file (/proc/devinfo/ufs) -> typ ColorOS.
     */
    private class Translator(donorCil: String, val donor: Ctx, baseTexts: Collection<String>, val base: Ctx, val types: Set<String>) {
        private val dGenfs = GENFS.findAll(donorCil).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
        private val bGenfs by lazy { baseTexts.flatMap { t -> GENFS.findAll(t).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } } }
        private val bFile by lazy { ctxLines(base.file).mapNotNull { l -> if (l.size < 2) null else Pair(l[0], ctxType(l.last())) }.toList() }
        private val bFileRe by lazy { bFile.map { (r, _) -> try { Regex(r) } catch (_: Exception) { null } } }
        private val bProp by lazy { ctxLines(base.property).mapNotNull { l -> if (l.size < 2) null else Triple(l[0], ctxType(l[1]), l.getOrNull(2) == "exact") }.toList() }
        private val bSvc by lazy { ctxLines(base.service).mapNotNull { l -> if (l.size < 2) null else l[0] to ctxType(l[1]) }.toMap() }
        private val bHw by lazy { ctxLines(base.hwservice).mapNotNull { l -> if (l.size < 2) null else l[0] to ctxType(l[1]) }.toMap() }
        private val cache = HashMap<String, String?>()
        private val dFile = ctxLines(donor.file).filter { it.size >= 2 }.map { it[0] to ctxType(it.last()) }.toList()
        private val dProp = ctxLines(donor.property).filter { it.size >= 2 }.map { it[0] to ctxType(it[1]) }.toList()
        private val dSvc = ctxLines(donor.service).filter { it.size >= 2 }.map { it[0] to ctxType(it[1]) }.toList()
        private val dHw = ctxLines(donor.hwservice).filter { it.size >= 2 }.map { it[0] to ctxType(it[1]) }.toList()

        fun of(t: String): String? = cache.getOrPut(t) { find(t) }

        private fun literal(re: String): String? {
            var r = re.removeSuffix("(/.*)?").removeSuffix("(/.*)")
            r = r.replace(Regex("""\([^()]*\)\?"""), "")
            r = r.replace(Regex("""\(([^()|]*)(\|[^()]*)?\)"""), "$1")
            r = r.replace(Regex("""\\(.)"""), "$1")
            return r.takeIf { x -> x.startsWith("/") && x.none { it in RE_META } }
        }

        private fun find(t: String): String? {
            val votes = ArrayList<String>()
            for ((fs, path, ty) in dGenfs) if (ty == t) {
                bGenfs.filter { it.first == fs && path.startsWith(it.second) }.maxByOrNull { it.second.length }?.third?.let { votes += it }
            }
            for ((re, ty0) in dFile) {
                if (ty0 != t) continue
                val exact = bFile.lastOrNull { it.first == re }?.second
                if (exact != null) { votes += exact; continue }
                val p = literal(re) ?: continue
                var best: String? = null; var score = -1
                bFile.forEachIndexed { i, (r, ty) ->
                    if (ty == null || bFileRe[i]?.matches(p) != true) return@forEachIndexed
                    val stem = r.indexOfFirst { it in RE_META }.let { if (it < 0) r.length + 1000 else it }
                    if (stem >= score) { score = stem; best = ty }
                }
                best?.let { votes += it }
            }
            for ((n, ty0) in dProp) {
                if (ty0 != t) continue
                (bProp.lastOrNull { it.first == n }?.second
                    ?: bProp.filter { !it.third && n.startsWith(it.first) }.maxByOrNull { it.first.length }?.second)?.let { votes += it }
            }
            for ((n, ty0) in dSvc) if (ty0 == t) bSvc[n]?.let { votes += it }
            for ((n, ty0) in dHw) if (ty0 == t) bHw[n]?.let { votes += it }
            return votes.filter { it in types && it != t }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
    }

    private val KINDS = setOf("allow", "auditallow", "dontaudit", "allowx", "auditallowx", "dontauditx",
        "typetransition", "typechange", "typemember", "typeattributeset")
    private val OPS = setOf("and", "or", "not", "xor", "all", "self")
    private val XOPS = setOf("range", "and", "or", "not", "xor", "all")
    private val NUM = Regex("""0x[0-9a-fA-F]+|\d+""")
    private val TOKEN = Regex("""\(|\)|"[^"]*"|[^\s()"]+""")
    private val TYPE_DECL = Regex("""\((?:type|typealias)\s+([A-Za-z0-9_.]+)\)""")
    private val DECL = Regex("""\((?:type|typeattribute|typealias|role|roleattribute|boolean|tunable)\s+([A-Za-z0-9_.]+)[\s)]""")

    fun stripComments(t: String): String = t.lineSequence().joinToString("\n") { it.substringBefore(';') }

    /** Wyrazenia najwyzszego poziomu, kazde w jednej linii (tekst bez komentarzy). */
    fun statements(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inStr = false
        var space = false
        for (c in text) {
            if (depth == 0 && c != '(') continue
            if (inStr) { sb.append(c); if (c == '"') inStr = false; continue }
            when {
                c == '"' -> { if (space && sb.last() != '(') sb.append(' '); space = false; sb.append(c); inStr = true }
                c.isWhitespace() -> space = sb.isNotEmpty()
                else -> {
                    if (space && c != ')' && sb.last() != '(') sb.append(' ')
                    space = false
                    sb.append(c)
                    if (c == '(') depth++
                    if (c == ')') { depth--; if (depth == 0) { out += sb.toString(); sb.setLength(0) } }
                }
            }
        }
        return out
    }

    fun parse(s: String): List<Any>? {
        val tok = TOKEN.findAll(s).map { it.value }.toList()
        var i = 0
        fun one(): Any {
            if (i >= tok.size) throw IllegalArgumentException("cil")
            if (tok[i] == ")") throw IllegalArgumentException("cil")
            if (tok[i] != "(") return tok[i++]
            i++
            val l = ArrayList<Any>()
            while (true) {
                if (i >= tok.size) throw IllegalArgumentException("cil")
                if (tok[i] == ")") { i++; return l }
                l += one()
            }
        }
        return try { (one() as? List<Any>)?.takeIf { i == tok.size } } catch (_: Exception) { null }
    }

    fun write(x: Any): String = if (x is List<*>) x.joinToString(" ", "(", ")") { write(it!!) } else x.toString()

    private fun atoms(x: Any?): List<String> = when (x) {
        is String -> listOf(x)
        is List<*> -> x.flatMap { atoms(it) }
        else -> emptyList()
    }

    private fun mapAtoms(x: Any, f: (String) -> String): Any = when (x) {
        is String -> if (x.startsWith("\"")) x else f(x)
        is List<*> -> x.map { mapAtoms(it!!, f) }
        else -> x
    }

    /** klasa -> uprawnienia (wlasne + wspolne z classcommon) z plat_sepolicy.cil */
    fun classPerms(plat: String): Map<String, Set<String>> {
        val commons = HashMap<String, List<String>>()
        val own = HashMap<String, List<String>>()
        val cc = HashMap<String, String>()
        for (s in statements(plat)) {
            if (!s.startsWith("(common ") && !s.startsWith("(class ") && !s.startsWith("(classcommon ")) continue
            val e = parse(s) ?: continue
            val name = e.getOrNull(1) as? String ?: continue
            when (e[0]) {
                "common" -> commons[name] = atoms(e.getOrNull(2))
                "class" -> own[name] = atoms(e.getOrNull(2))
                "classcommon" -> (e.getOrNull(2) as? String)?.let { cc[name] = it }
            }
        }
        return own.mapValues { (c, p) -> (p + (cc[c]?.let { commons[it] } ?: emptyList())).toSet() }
    }

    private class Checker(val known: Set<String>, val types: Set<String>, val classes: Map<String, Set<String>>) {
        fun names(x: Any?) = atoms(x).all { it in OPS || it in known }
        fun valid(e: List<Any>): Boolean {
            val head = e.getOrNull(0) as? String ?: return false
            return when (head) {
                "allow", "auditallow", "dontaudit" -> {
                    if (e.size != 4 || !names(e[1]) || !names(e[2])) return false
                    val cp = e[3] as? List<*> ?: return false
                    val perms = classes[cp.getOrNull(0) as? String ?: return false] ?: return false
                    cp.size == 2 && atoms(cp[1]).all { it in perms || it in OPS }
                }
                "allowx", "auditallowx", "dontauditx" -> {
                    if (e.size != 4 || !names(e[1]) || !names(e[2])) return false
                    val xp = e[3] as? List<*> ?: return false
                    xp.size == 3 && xp[0] is String && (xp[1] as? String)?.let { it in classes } == true &&
                        atoms(xp[2]).all { it in XOPS || NUM.matches(it) }
                }
                "typetransition" -> {
                    if (e.size !in 5..6 || e.drop(1).any { it !is String }) return false
                    val a = e.map { it as String }
                    a[1] in known && a[2] in known && a[3] in classes && a.last() in types &&
                        (e.size == 5 || a[4].startsWith("\""))
                }
                "typechange", "typemember" -> e.size == 5 && e.drop(1).all { it is String } &&
                    e[1].toString() in known && e[2].toString() in known && e[3].toString() in classes && e[4].toString() in types
                "typeattributeset" -> e.size == 3 && e[1] is String && e[1].toString() in known && names(e[2])
                else -> false
            }
        }
    }

    /** Pola linii seapp_contexts (klucz=wartosc). */
    private fun seapp(text: String): List<Map<String, String>> = text.lineSequence()
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .map { l -> l.split(Regex("""\s+""")).mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap() }
        .toList()

    /**
     * @param texts wejscia secilc bez komentarzy (klucze jak w Franken.checkSepolicy)
     * @param known wszystkie zadeklarowane nazwy + puste atrybuty dopisane przez Frankena
     * @param ver wersja vendora bazy (plat_sepolicy_vers.txt), np. 202504
     */
    fun build(
        texts: Map<String, String>, known: Set<String>, ver: String,
        donorSeapp: String, baseSeapp: String, donorVendorCil: String?, donorVer: String?,
        donorCtx: Ctx = Ctx(), baseCtx: Ctx = Ctx(),
    ): Result? {
        val plat = texts["plat_sepolicy.cil"] ?: return null
        val donorPart = listOfNotNull(texts["system_ext_sepolicy.cil"], texts["product_sepolicy.cil"])
        val vendorSide = listOfNotNull(texts["vendor_sepolicy.cil"], texts["odm_sepolicy.cil"])
        val donorTypes = donorPart.flatMapTo(HashSet()) { t -> TYPE_DECL.findAll(t).map { it.groupValues[1] } }
        val platDecl = DECL.findAll(plat).mapTo(HashSet()) { it.groupValues[1] }
        val vendDecl = vendorSide.flatMapTo(HashSet()) { t -> DECL.findAll(t).map { it.groupValues[1] } }

        val suffix = "_" + ver.replace('.', '_')
        val vendNames = vendorSide.flatMapTo(HashSet()) { t -> Regex("""[A-Za-z0-9_.]+""").findAll(t).map { it.value } }
        val donorLines = seapp(donorSeapp)
        val domains = LinkedHashMap<String, MutableList<String>>()
        for (l in donorLines) {
            val d = l["domain"] ?: continue
            // vendor bazy juz ma reguly dla tej domeny (np. vendor_qtelephony_202504 przez mapping system_ext) - nie ruszac
            if (d !in donorTypes || d in platDecl || d in vendDecl || d in vendNames || (d + suffix) in vendNames) continue
            val pk = domains.getOrPut(d) { ArrayList() }
            l["name"]?.let { if (it !in pk) pk += it }
        }
        if (domains.isEmpty()) return null

        val newTypes = domains.keys.map { it + "_userfaultfd" }.filter { it !in known }.toSet()
        val allKnown = known + newTypes
        val types = texts.values.flatMapTo(HashSet()) { t -> TYPE_DECL.findAll(t).map { it.groupValues[1] } } + newTypes
        val chk = Checker(allKnown, types, classPerms(plat))

        val rules = LinkedHashSet<String>()
        val essential = LinkedHashSet<String>()
        var rejected = 0
        var fromDonor = 0
        val fromBase = LinkedHashMap<String, Int>()
        fun add(e: List<Any>, ess: Boolean = false): Boolean {
            if (!chk.valid(e)) { rejected++; return false }
            val s = write(e)
            if (!rules.add(s)) return false
            if (ess) essential += s
            return true
        }

        val donorStmts = donorVendorCil?.let { statements(stripComments(it)) }.orEmpty()
        val xl = donorVendorCil?.let { Translator(stripComments(it), donorCtx, texts.values, baseCtx, types) }
        val translated = LinkedHashMap<String, String>()
        val donorSuffix = donorVer?.takeIf { it.isNotBlank() }?.let { "_" + it.trim().replace('.', '_') }
        val baseStmts = vendorSide.flatMap { statements(it) }

        for ((d, pkgs) in domains) {
            // 1. app_domain() + net_domain()
            val uf = d + "_userfaultfd"
            if (uf in newTypes) {
                rules += "(type $uf)"; essential += "(type $uf)"
                if ("object_r" in known) { rules += "(roletype object_r $uf)"; essential += "(roletype object_r $uf)" }
                add(listOf("typetransition", d, d, "anon_inode", "\"[userfaultfd]\"", uf), true)
                add(listOf("allow", d, uf, listOf("anon_inode", listOf("create", "ioctl", "read"))), true)
            }
            add(listOf("typetransition", d, "tmpfs", "file", "appdomain_tmpfs"), true)
            add(listOf("allow", d, "appdomain_tmpfs", listOf("file", listOf("execute", "getattr", "map", "read", "write"))), true)
            add(listOf("typeattributeset", "netdomain", listOf(d)), true)

            fun mine(a: String) = a == d || (a.startsWith(d + "_") && a in allKnown && a !in platDecl)

            // 2. vendor dawcy
            for (s in donorStmts) {
                if (!s.contains(d)) continue
                val e0 = parse(s) ?: continue
                if (e0[0].toString() !in KINDS) continue
                @Suppress("UNCHECKED_CAST")
                val e1 = (if (donorSuffix == null) e0 else mapAtoms(e0) { a -> if (a.endsWith(donorSuffix) && a.length > donorSuffix.length) a.removeSuffix(donorSuffix) else a }) as List<Any>
                if (atoms(e1).none { mine(it) }) continue
                // typy vendora dawcy, ktorych baza nie ma -> typ bazy dla tego samego miejsca
                val used = HashMap<String, String>()
                @Suppress("UNCHECKED_CAST")
                val e = if (xl == null) e1 else mapAtoms(e1) { a ->
                    if (a in allKnown || a in OPS || a in KINDS || NUM.matches(a)) a else xl.of(a)?.also { used[a] = it } ?: a
                } as List<Any>
                if (atoms(e).any { it.startsWith("base_typeattr") }) continue
                val r = if (e[0] == "typeattributeset") {
                    val members = (e.getOrNull(2) as? List<*>)?.takeIf { l -> l.all { it is String } }?.map { it as String } ?: continue
                    val keep = members.filter { mine(it) }.takeIf { it.isNotEmpty() } ?: continue
                    listOf("typeattributeset", e[1], keep)
                } else e
                if (add(r)) { fromDonor++; atoms(r).forEach { a -> used.entries.firstOrNull { it.value == a }?.let { translated[it.key] = it.value } } }
            }

            // 3. vendor bazy: domena tego pakietu w bazie albo platform_app (seinfo=platform)
            val baseLines = seapp(baseSeapp)
            val baseDom = pkgs.firstNotNullOfOrNull { p -> baseLines.firstOrNull { it["name"] == p }?.get("domain") }
                ?: if (donorLines.any { it["domain"] == d && it["seinfo"] == "platform" }) "platform_app" else null
            val tok = baseDom?.let { b -> when { (b + suffix) in known -> b + suffix; b in vendDecl -> b; else -> null } }
            if (baseDom != null && tok != null && baseDom != d) {
                var n = 0
                for (s in baseStmts) {
                    if (!s.contains(tok)) continue
                    val e = parse(s) ?: continue
                    if (e[0].toString() !in KINDS || tok !in atoms(e)) continue
                    val r: List<Any> = if (e[0] == "typeattributeset") {
                        val members = (e.getOrNull(2) as? List<*>)?.takeIf { l -> l.all { it is String } } ?: continue
                        if (tok !in members) continue
                        listOf("typeattributeset", e[1], listOf(d))
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        mapAtoms(e) { if (it == tok) d else it } as List<Any>
                    }
                    if (add(r)) n++
                }
                fromBase["$d<-$baseDom"] = n
            }
        }
        return Result(domains, rules.toList(), essential, fromDonor, fromBase, rejected, translated)
    }
}
