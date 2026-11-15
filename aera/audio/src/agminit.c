/* Preloaded into the stock agmplay.
 * 1. Calls agm_init() before the first tinyalsa call: the engine library stands in
 *    for the client library in the same process, so nobody else would initialize it.
 * 2. Diagnostics: logs who waits on OSAL signals and what is sent over GPR, with the
 *    caller's library and offset, so a stalled init can be located in the binaries. */
typedef struct { const char *dli_fname; void *dli_fbase; const char *dli_sname; void *dli_saddr; } Dl_info;
extern int agm_init(void);
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...);
extern void *dlsym(void *handle, const char *symbol);
extern int dladdr(const void *addr, Dl_info *info);
#define NEXT ((void *)-1L)
#define TAG "neo8-agm"

typedef long (*fn8)(long, long, long, long, long, long, long, long);
#ifdef NEO8_AGM_TRACE
static const char *base(const char *p) {
    const char *b = p ? p : "?";
    for (const char *c = b; *c; c++) if (*c == '/') b = c + 1;
    return b;
}
static void where(void *ra, const char **lib, unsigned long *off) {
    Dl_info i = {0, 0, 0, 0};
    *lib = "?"; *off = (unsigned long)ra;
    if (dladdr(ra, &i) && i.dli_fname) { *lib = base(i.dli_fname); *off = (unsigned long)ra - (unsigned long)i.dli_fbase; }
}

long ar_osal_signal_timedwait(long a0, long a1, long a2, long a3, long a4, long a5, long a6, long a7) {
    static fn8 real;
    const char *lib; unsigned long off;
    if (!real) real = (fn8)dlsym(NEXT, "ar_osal_signal_timedwait");
    where(__builtin_return_address(0), &lib, &off);
    __android_log_print(4, TAG, "timedwait enter sig=%lx timeout_ns=%ld caller=%s+0x%lx", a0, a1, lib, off);
    long rc = real ? real(a0, a1, a2, a3, a4, a5, a6, a7) : -1;
    __android_log_print(4, TAG, "timedwait exit rc=%d caller=%s+0x%lx", (int)rc, lib, off);
    return rc;
}
static void dump(const char *what, const unsigned int *w, void *ra) {
    const char *lib; unsigned long off;
    where(ra, &lib, &off);
    if (!w) { __android_log_print(4, TAG, "%s null caller=%s+0x%lx", what, lib, off); return; }
    __android_log_print(4, TAG, "%s %08x %08x %08x %08x %08x %08x %08x %08x caller=%s+0x%lx", what,
                        w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], lib, off);
}
#define WRAP(name)                                                                              \
    long name(long a0, long a1, long a2, long a3, long a4, long a5, long a6, long a7) {       \
        static fn8 real;                                                                        \
        if (!real) real = (fn8)dlsym(NEXT, #name);                                              \
        dump(#name, (const unsigned int *)a0, __builtin_return_address(0));                     \
        long rc = real ? real(a0, a1, a2, a3, a4, a5, a6, a7) : -1;                             \
        __android_log_print(4, TAG, #name " rc=0x%x", (unsigned)rc);                            \
        return rc;                                                                              \
    }
WRAP(__gpr_cmd_async_send)
WRAP(__gpr_cmd_alloc_send)

#endif

/* agm_init() must not run from a constructor: the engine starts its GPR receiver
 * thread, that thread dlopen()s a thread extension and blocks on the loader lock
 * held while constructors run, so the reply to the first DSP command is never
 * processed and init times out, then deadlocks joining the thread (probe 6).
 * Initialize on the first tinyalsa call from main() instead. */
static int started;
static void neo8_agm_bootstrap(const char *from) {
    if (started) return;
    started = 1;
    __android_log_print(4, TAG, "calling in-process agm_init from %s", from);
    int rc = agm_init();
    __android_log_print(rc ? 6 : 4, TAG, "in-process agm_init returned %d", rc);
}
#define LAZY(name)                                                                              \
    long name(long a0, long a1, long a2, long a3, long a4, long a5, long a6, long a7) {       \
        static fn8 real;                                                                        \
        neo8_agm_bootstrap(#name);                                                              \
        if (!real) real = (fn8)dlsym(NEXT, #name);                                              \
        long rc = real ? real(a0, a1, a2, a3, a4, a5, a6, a7) : 0;                              \
        __android_log_print(4, TAG, #name "(%ld, %ld) returned 0x%lx", a0, a1, rc);            \
        return rc;                                                                              \
    }
LAZY(mixer_open)
LAZY(pcm_open)
