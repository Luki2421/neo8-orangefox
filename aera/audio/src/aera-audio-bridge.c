/* AERA audio bridge for the Neo8 plus small mixer/diagnostic commands (static). */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <stddef.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <sound/asound.h>

static int open_card(const char *card) {
    char path[64];
    snprintf(path, sizeof path, "/dev/snd/controlC%s", card);
    int fd = open(path, O_RDWR);
    if (fd < 0) fprintf(stderr, "open %s: %s\n", path, strerror(errno));
    return fd;
}

static const char *type_name(int t) {
    switch (t) {
    case SNDRV_CTL_ELEM_TYPE_BOOLEAN: return "BOOL";
    case SNDRV_CTL_ELEM_TYPE_INTEGER: return "INT";
    case SNDRV_CTL_ELEM_TYPE_ENUMERATED: return "ENUM";
    case SNDRV_CTL_ELEM_TYPE_BYTES: return "BYTES";
    case SNDRV_CTL_ELEM_TYPE_INTEGER64: return "INT64";
    default: return "OTHER";
    }
}

static int enum_item(int fd, const struct snd_ctl_elem_id *id, unsigned item, char *out, size_t n) {
    struct snd_ctl_elem_info info;
    memset(&info, 0, sizeof info);
    info.id = *id;
    info.value.enumerated.item = item;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) return -1;
    snprintf(out, n, "%s", info.value.enumerated.name);
    return 0;
}

static void print_value(int fd, const struct snd_ctl_elem_info *info) {
    struct snd_ctl_elem_value v;
    memset(&v, 0, sizeof v);
    v.id = info->id;
    printf("%-44s %-5s n=%u", (const char *)info->id.name, type_name(info->type), info->count);
    if (info->type == SNDRV_CTL_ELEM_TYPE_BYTES || !(info->access & SNDRV_CTL_ELEM_ACCESS_READ) ||
        ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &v) < 0) { printf("\n"); return; }
    printf(" =");
    for (unsigned i = 0; i < info->count && i < 4; i++) {
        if (info->type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
            char name[64] = "?";
            enum_item(fd, &info->id, v.value.enumerated.item[i], name, sizeof name);
            printf(" %s", name);
        } else if (info->type == SNDRV_CTL_ELEM_TYPE_INTEGER64) {
            printf(" %lld", (long long)v.value.integer64.value[i]);
        } else {
            printf(" %ld", v.value.integer.value[i]);
        }
    }
    if (info->type == SNDRV_CTL_ELEM_TYPE_INTEGER)
        printf("  [%ld..%ld]", info->value.integer.min, info->value.integer.max);
    if (info->type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) printf("  (%u items)", info->value.enumerated.items);
    printf("\n");
}

static int cmd_list(const char *card, const char *filter) {
    int fd = open_card(card);
    if (fd < 0) return 1;
    struct snd_ctl_elem_list list;
    memset(&list, 0, sizeof list);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { perror("ELEM_LIST"); return 1; }
    unsigned count = list.count;
    struct snd_ctl_elem_id *ids = calloc(count ? count : 1, sizeof *ids);
    memset(&list, 0, sizeof list);
    list.space = count;
    list.pids = ids;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &list) < 0) { perror("ELEM_LIST ids"); return 1; }
    unsigned shown = 0;
    for (unsigned i = 0; i < list.used; i++) {
        if (filter && !strcasestr((const char *)ids[i].name, filter)) continue;
        struct snd_ctl_elem_info info;
        memset(&info, 0, sizeof info);
        info.id = ids[i];
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) continue;
        print_value(fd, &info);
        shown++;
    }
    printf("%u of %u controls shown\n", shown, count);
    return 0;
}

static int cmd_set(const char *card, const char *name, const char *value) {
    int fd = open_card(card);
    if (fd < 0) return 1;
    struct snd_ctl_elem_info info;
    memset(&info, 0, sizeof info);
    info.id.iface = SNDRV_CTL_ELEM_IFACE_MIXER;
    snprintf((char *)info.id.name, sizeof info.id.name, "%s", name);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info) < 0) {
        fprintf(stderr, "control '%s' not found: %s\n", name, strerror(errno));
        return 1;
    }
    struct snd_ctl_elem_value v;
    memset(&v, 0, sizeof v);
    v.id = info.id;
    long number = 0;
    if (info.type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) {
        int found = -1;
        for (unsigned i = 0; i < info.value.enumerated.items; i++) {
            char item[64];
            if (enum_item(fd, &info.id, i, item, sizeof item) == 0 && !strcmp(item, value)) { found = (int)i; break; }
        }
        if (found < 0) {
            char *end;
            long n = strtol(value, &end, 0);
            if (*end || n < 0 || (unsigned long)n >= info.value.enumerated.items) {
                fprintf(stderr, "'%s' is not an item of '%s'; items:", value, name);
                for (unsigned i = 0; i < info.value.enumerated.items; i++) {
                    char item[64] = "?";
                    enum_item(fd, &info.id, i, item, sizeof item);
                    fprintf(stderr, " [%s]", item);
                }
                fprintf(stderr, "\n");
                return 1;
            }
            found = (int)n;
        }
        for (unsigned i = 0; i < info.count && i < 128; i++) v.value.enumerated.item[i] = (unsigned)found;
    } else if (info.type == SNDRV_CTL_ELEM_TYPE_INTEGER || info.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN) {
        char *end;
        if (!strcasecmp(value, "on")) number = 1;
        else if (!strcasecmp(value, "off")) number = 0;
        else { number = strtol(value, &end, 0); if (*end) { fprintf(stderr, "bad number '%s'\n", value); return 1; } }
        for (unsigned i = 0; i < info.count && i < 128; i++) v.value.integer.value[i] = number;
    } else {
        fprintf(stderr, "control '%s' has unsupported type %s\n", name, type_name(info.type));
        return 1;
    }
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &v) < 0) {
        fprintf(stderr, "write '%s'=%s failed: %s\n", name, value, strerror(errno));
        return 1;
    }
    memset(&info.value, 0, sizeof info.value);
    ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &info);
    print_value(fd, &info);
    return 0;
}

static void le16(FILE *f, unsigned v) { fputc(v & 255, f); fputc((v >> 8) & 255, f); }
static void le32(FILE *f, uint32_t v) { le16(f, v & 0xffff); le16(f, v >> 16); }

/* 48 kHz stereo 16-bit; two beeps so that a constant hum cannot be mistaken for it. */
static int cmd_tone(const char *path, double seconds, double hz, double amplitude) {
    const unsigned rate = 48000, channels = 2;
    if (seconds <= 0 || seconds > 30 || amplitude <= 0 || amplitude > 0.25) { fprintf(stderr, "refusing tone parameters\n"); return 1; }
    uint32_t frames = (uint32_t)(seconds * rate), bytes = frames * channels * 2;
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); return 1; }
    fwrite("RIFF", 1, 4, f); le32(f, 36 + bytes); fwrite("WAVEfmt ", 1, 8, f);
    le32(f, 16); le16(f, 1); le16(f, channels); le32(f, rate); le32(f, rate * channels * 2);
    le16(f, channels * 2); le16(f, 16); fwrite("data", 1, 4, f); le32(f, bytes);
    for (uint32_t i = 0; i < frames; i++) {
        double t = (double)i / rate, phase = fmod(t, 1.0), gate = phase < 0.6 ? 1.0 : 0.0;
        double fade = phase < 0.02 ? phase / 0.02 : (phase > 0.58 && phase < 0.6 ? (0.6 - phase) / 0.02 : 1.0);
        int16_t s = (int16_t)(sin(2 * M_PI * hz * t) * amplitude * gate * fade * 32767.0);
        for (unsigned c = 0; c < channels; c++) le16(f, (uint16_t)s);
    }
    if (fclose(f)) { perror("close"); return 1; }
    printf("wrote %s: %u frames, %u Hz, 2 ch, 16 bit\n", path, frames, rate);
    return 0;
}

/* Talk to the ADSP directly over the audio packet device, without any vendor library:
 * send APM_CMD_GET_SPF_STATE and wait for APM_CMD_RSP_GET_SPF_STATE. */
static int cmd_gprping(const char *dev, int wait_ms) {
    struct { uint32_t header; uint8_t dst_domain, src_domain, client_data, reserved;
             uint32_t src_port, dst_port, token, opcode; } pkt;
    _Static_assert(sizeof pkt == 24, "gpr header");
    memset(&pkt, 0, sizeof pkt);
    pkt.header = 0u | (6u << 4) | (24u << 8);   /* version 0, 6 header words, 24 bytes */
    pkt.dst_domain = 2;                         /* ADSP */
    pkt.src_domain = 3;                         /* APPS */
    pkt.src_port = 0x2A2A;
    pkt.dst_port = 1;                           /* APM module instance */
    pkt.token = 0x4E454F38;
    pkt.opcode = 0x01001021;                    /* APM_CMD_GET_SPF_STATE */
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { printf("gprping: open %s failed: %s\n", dev, strerror(errno)); return 1; }
    ssize_t n = write(fd, &pkt, sizeof pkt);
    printf("gprping: write returned %zd%s%s\n", n, n < 0 ? " errno " : "", n < 0 ? strerror(errno) : "");
    if (n < 0) return 1;
    int waited = 0, got = 0;
    while (waited < wait_ms) {
        struct pollfd p = { fd, POLLIN, 0 };
        int r = poll(&p, 1, 250);
        waited += 250;
        if (r <= 0) continue;
        uint32_t buf[256];
        n = read(fd, buf, sizeof buf);
        if (n < 0) { if (errno == EAGAIN) continue; printf("gprping: read failed: %s\n", strerror(errno)); return 1; }
        printf("gprping: %zd bytes after <=%d ms:", n, waited);
        for (ssize_t i = 0; i < n / 4 && i < 12; i++) printf(" %08x", buf[i]);
        printf("\n");
        if (n >= 24) printf("gprping: opcode 0x%08x token 0x%08x%s\n", buf[5], buf[4],
                            buf[5] == 0x02001007 ? "  <- SPF state response" : "");
        got++;
        if (n >= 24 && buf[5] == 0x02001007) break;
    }
    if (!got) printf("gprping: no packet from the ADSP within %d ms\n", wait_ms);
    close(fd);
    return got ? 0 : 1;
}

/* ---- AERA audio bridge prototype ------------------------------------------------
 * Protocol of aera-audio-null-bridge: abstract socket "aera-browser-audio-v1",
 * hello {0x41525041, 48000, 2, 16}, then raw S16LE stereo. The PCM is fed to the stock
 * player through a FIFO that looks like an endless WAV file; silence fills the gaps. */
#define CHUNK 3840 /* 20 ms */
static volatile sig_atomic_t g_stop;
static void on_stop(int s) { (void)s; g_stop = 1; }
static double now_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
static socklen_t bridge_address(struct sockaddr_un *a) {
    static const char name[] = "aera-browser-audio-v1";
    memset(a, 0, sizeof(*a)); a->sun_family = AF_UNIX;
    memcpy(a->sun_path + 1, name, sizeof(name) - 1);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(name) - 1);
}
static void put32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

/* Volume: AERA's hardware keys write 0..100 (default 30) to /tmp/aera-audio-volume.
 * That percentage scales a ceiling (percent of full scale, default 80) that can be
 * changed in /sdcard/AERA/audio-volume, because recovery has no speaker calibration. */
static int read_percent(const char *path, int fallback, int low) {
    char text[16] = {0}; int value = fallback;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { if (read(fd, text, sizeof(text) - 1) > 0) { int v = atoi(text); if (v >= low && v <= 100) value = v; } close(fd); }
    return value;
}
/* Q16 gain */
/* Stereo orientation. AERA writes the UI rotation (0, 90 or 270, Android
 * convention) to /tmp/aera-audio-rotation. On the Neo8 the left channel drives
 * the TFA98xx at 0x34 (profile "speaker", bottom) and the right channel the one
 * at 0x35 ("speakerR", top). Holding the phone with its top to the left
 * (rotation 90) puts the right channel on the left, so "auto" swaps there.
 * /sdcard/AERA/audio-channels overrides: auto, normal or swap. */
static int want_swap(void) {
    char mode[16] = {0};
    int fd = open("/data/media/0/AERA/audio-channels", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { if (read(fd, mode, sizeof(mode) - 1) < 0) mode[0] = 0; close(fd); }
    if (!strncmp(mode, "swap", 4)) return 1;
    if (!strncmp(mode, "normal", 6)) return 0;
    char rotation[16] = {0};
    fd = open("/tmp/aera-audio-rotation", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { if (read(fd, rotation, sizeof(rotation) - 1) < 0) rotation[0] = 0; close(fd); }
    return atoi(rotation) == 90;
}

static int32_t target_gain(int ceiling) {
    int volume = read_percent("/tmp/aera-audio-volume", 30, 0);
    return (int32_t)((int64_t)65536 * ceiling * volume / 10000);
}

#define RING (192000)          /* 1 s */
#define PREBUFFER (3 * CHUNK)  /* 60 ms collected before (re)starting a client stream */
#define HIGH_WATER (96000)     /* 500 ms: the client is far ahead, drop the oldest audio */
#define LOW_WATER (48000)      /* ... down to 250 ms */
static uint8_t ring[RING];
static size_t ring_head, ring_fill;
static void ring_put(const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) ring[(ring_head + ring_fill + i) % RING] = p[i];
    ring_fill += n;
}
static void ring_get(uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) p[i] = ring[(ring_head + i) % RING];
    ring_head = (ring_head + n) % RING; ring_fill -= n;
}
static void ring_drop(size_t n) { ring_head = (ring_head + n) % RING; ring_fill -= n; }

static int cmd_bridge(const char *fifo_path, long extra_uid, char **player) {
    const int ceiling = read_percent("/data/media/0/AERA/audio-volume", 80, 1);
    signal(SIGPIPE, SIG_IGN); signal(SIGTERM, on_stop); signal(SIGINT, on_stop);
    prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0);
    /* The UI and browser compete for the CPU; the player inherits this. */
    setpriority(PRIO_PROCESS, 0, -16);
    struct sockaddr_un address; socklen_t size = bridge_address(&address);
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (server < 0 || bind(server, (struct sockaddr *)&address, size) || listen(server, 1)) { perror("bridge socket"); return 70; }
    unlink(fifo_path);
    if (mkfifo(fifo_path, 0600)) { perror("mkfifo"); return 70; }
    int fifo = open(fifo_path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fifo < 0) { perror("open fifo"); return 70; }
    int pipe_size = fcntl(fifo, F_SETPIPE_SZ, 8192);
    uint8_t header[44] = "RIFF\0\0\0\0WAVEfmt ";
    put32(header + 4, 0x7ffff000u + 36); put32(header + 16, 16); header[20] = 1; header[22] = 2;
    put32(header + 24, 48000); put32(header + 28, 192000); header[32] = 4; header[34] = 16;
    memcpy(header + 36, "data", 4); put32(header + 40, 0x7ffff000u);
    if (write(fifo, header, sizeof(header)) != sizeof(header)) { perror("fifo header"); return 70; }
    pid_t child = fork();
    if (!child) {
        prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
        execv(player[0], player); perror("exec player"); _exit(127);
    }
    if (child < 0) { perror("fork"); return 70; }
    const double start = now_s();
    fprintf(stderr, "bridge: listening, pipe %d bytes, player pid %d, ceiling %d%%\n", pipe_size, (int)child, ceiling);
    /* The FIFO is writable at once and agmplay reads its header (plus a stdio
     * buffer) long before agm_init and pcm_open finish. Playback has only begun
     * once the pipe was full and the player then took several more chunks. */
    int client = -1, rc = 0, announced = 0, streaming = 0, pipe_was_full = 0, taken_after_full = 0;
    int32_t gain = target_gain(ceiling), target = gain;
    double next_volume = 0, client_since = 0;
    int swap = want_swap();
    unsigned long long chunks = 0, clients = 0, underruns = 0, dropped = 0, client_bytes = 0;
    uint8_t chunk[CHUNK], buffer[16384];
    while (!g_stop) {
        int status;
        if (child > 0 && waitpid(child, &status, WNOHANG) == child) {
            /* Like the silent fallback: keep accepting PCM so that media features stay usable. */
            fprintf(stderr, "bridge: player exited, status 0x%x at %.2fs; continuing silently\n", status, now_s() - start);
            child = -1;
        }
        if (client < 0) {
            client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
            if (client >= 0) {
                struct ucred peer = {0}; socklen_t n = sizeof(peer); uint32_t hello[4] = {0}; size_t got = 0;
                struct timeval timeout = {0, 300000}; setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
                int ok = !getsockopt(client, SOL_SOCKET, SO_PEERCRED, &peer, &n) && n == sizeof(peer) &&
                    (peer.uid == 99090 || peer.uid == 99092 || peer.uid == 99094 || (extra_uid >= 0 && (long)peer.uid == extra_uid));
                while (ok && got < sizeof(hello)) { ssize_t r = read(client, (uint8_t *)hello + got, sizeof(hello) - got); if (r <= 0) ok = 0; else got += r; }
                ok = ok && hello[0] == 0x41525041u && hello[1] == 48000 && hello[2] == 2 && hello[3] == 16;
                fprintf(stderr, "bridge: client uid %d %s at %.2fs\n", (int)peer.uid, ok ? "accepted" : "rejected", now_s() - start);
                if (!ok) { close(client); client = -1; }
                else {
                    fcntl(client, F_SETFL, O_NONBLOCK); clients++; streaming = 0; ring_head = ring_fill = 0;
                    underruns = dropped = client_bytes = 0; client_since = now_s();
                }
            }
        }
        struct pollfd fds[2] = {{fifo, child > 0 ? POLLOUT : 0, 0}, {client, POLLIN, 0}};
        poll(fds, client >= 0 ? 2 : 1, 20);
        if (client >= 0) { /* take everything the client has sent */
            for (;;) {
                size_t room = RING - ring_fill; if (room > sizeof(buffer)) room = sizeof(buffer);
                if (!room) break;
                ssize_t r = read(client, buffer, room);
                if (r > 0) { ring_put(buffer, (size_t)r); client_bytes += r; continue; }
                if (r == 0 || (errno != EAGAIN && errno != EINTR)) {
                    fprintf(stderr, "bridge: client left at %.2fs after %.1fs: %.1fs of audio, %llu underruns, %.2fs dropped\n",
                            now_s() - start, now_s() - client_since, client_bytes / 192000.0, underruns, dropped / 192000.0);
                    close(client); client = -1;
                }
                break;
            }
            if (ring_fill > HIGH_WATER) {
                size_t n = (ring_fill - LOW_WATER) & ~(size_t)3; ring_drop(n); dropped += n;
            }
        }
        if (now_s() >= next_volume) {
            target = target_gain(ceiling);
            int wanted = want_swap();
            if (wanted != swap) fprintf(stderr, "bridge: channels %s at %.2fs\n", wanted ? "swapped" : "normal", now_s() - start);
            swap = wanted;
            next_volume = now_s() + 0.2;
        }
        if (child < 0) { if (client < 0) usleep(20000); else ring_drop(ring_fill & ~(size_t)3); continue; }
        if (!(fds[0].revents & POLLOUT)) { pipe_was_full = 1; continue; }
        if (pipe_was_full && !announced && ++taken_after_full >= 3) {
            announced = 1; fprintf(stderr, "bridge: player is consuming at %.2fs\n", now_s() - start);
        }
        memset(chunk, 0, sizeof(chunk));
        if (!streaming && (ring_fill >= PREBUFFER || (client < 0 && ring_fill >= 4))) streaming = 1;
        if (streaming) {
            size_t n = ring_fill < CHUNK ? ring_fill & ~(size_t)3 : CHUNK;
            ring_get(chunk, n);
            if (n < CHUNK) { if (client >= 0) underruns++; streaming = 0; }
        }
        int16_t *pcm = (int16_t *)chunk;
        if (swap) for (int i = 0; i < CHUNK / 2; i += 2) { int16_t left = pcm[i]; pcm[i] = pcm[i + 1]; pcm[i + 1] = left; }
        /* Apply the gain with a per-chunk ramp so that volume steps never click. */
        for (int i = 0; i < CHUNK / 2; i++) {
            int32_t g = gain + (int32_t)((int64_t)(target - gain) * (i / 2) / (CHUNK / 4));
            pcm[i] = (int16_t)(((int32_t)pcm[i] * g) >> 16);
        }
        gain = target;
        size_t done = 0; int tries = 0;
        while (done < sizeof(chunk) && tries++ < 50 && !g_stop) { /* never leave half a frame in the stream */
            ssize_t w = write(fifo, chunk + done, sizeof(chunk) - done);
            if (w > 0) done += w; else { struct pollfd again = {fifo, POLLOUT, 0}; poll(&again, 1, 100); }
        }
        if (done == sizeof(chunk)) chunks++; else if (done) { fprintf(stderr, "bridge: stream broken after a partial write\n"); rc = 70; break; }
    }
    if (client >= 0)
        fprintf(stderr, "bridge: client stopped with the bridge after %.1fs: %.1fs of audio, %llu underruns, %.2fs dropped\n",
                now_s() - client_since, client_bytes / 192000.0, underruns, dropped / 192000.0);
    /* The stock player closes its stream on SIGINT; AERA allows about 150 ms before SIGKILL. */
    if (child > 0) { kill(child, SIGINT); for (int i = 0; i < 10 && waitpid(child, NULL, WNOHANG) == 0; i++) usleep(10000); kill(child, SIGKILL); waitpid(child, NULL, 0); }
    fprintf(stderr, "bridge: stop at %.2fs: clients %llu, chunks %llu (%.1fs)\n", now_s() - start, clients, chunks, chunks * 0.02);
    close(fifo); unlink(fifo_path); close(server);
    return rc;
}

/* Test client that behaves like AERA's: 20 ms blocks on a monotonic clock.
 * 0-1 s tone 660 Hz, 1-2 s nothing sent at all, 2-3 s tone 880 Hz, 3-3.5 s zeros, 3.5-4.5 s tone 523 Hz. */
/* channel: 0 both, 1 left only, 2 right only */
static int cmd_client(double amplitude, int channel) {
    if (amplitude <= 0 || amplitude > 0.25) return 1;
    signal(SIGPIPE, SIG_IGN);
    struct sockaddr_un address; socklen_t size = bridge_address(&address);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0), connected = 0;
    for (int i = 0; i < 100 && !connected; i++) { if (!connect(fd, (struct sockaddr *)&address, size)) connected = 1; else usleep(20000); }
    if (!connected) { perror("client connect"); return 1; }
    const uint32_t hello[] = {0x41525041u, 48000, 2, 16};
    if (send(fd, hello, sizeof(hello), MSG_NOSIGNAL) != sizeof(hello)) { perror("hello"); return 1; }
    struct timespec deadline; clock_gettime(CLOCK_MONOTONIC, &deadline);
    int16_t pcm[1920]; unsigned long long sent = 0; int failures = 0;
    for (int block = 0; block < 225; block++) {
        double t0 = block * 0.02; int skip = t0 >= 1.0 && t0 < 2.0;
        double hz = t0 < 1.0 ? 660 : t0 < 3.0 ? 880 : 523.25, begin = t0 < 1.0 ? 0 : t0 < 3.0 ? 2.0 : 3.5, end = begin + 1.0;
        for (int i = 0; i < 960; i++) {
            double t = t0 + i / 48000.0, v = 0;
            if (t >= begin && t < end) { double fade = t - begin < 0.02 ? (t - begin) / 0.02 : end - t < 0.02 ? (end - t) / 0.02 : 1; v = sin(2 * M_PI * hz * t) * amplitude * fade; }
            pcm[2 * i] = channel == 2 ? 0 : (int16_t)(v * 32767);
            pcm[2 * i + 1] = channel == 1 ? 0 : (int16_t)(v * 32767);
        }
        if (!skip) { if (send(fd, pcm, sizeof(pcm), MSG_NOSIGNAL) == (ssize_t)sizeof(pcm)) sent += sizeof(pcm); else failures++; }
        deadline.tv_nsec += 20000000; if (deadline.tv_nsec >= 1000000000) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000; }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &deadline, NULL);
    }
    close(fd);
    printf("client: sent %llu bytes (%.2f s of audio), failed sends %d\n", sent, sent / 192000.0, failures);
    return failures ? 1 : 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--browser-audio")) { /* how AERA starts /system/bin/aera-audio-bridge */
        static char *player[] = {"/system/bin/sh", "/system/bin/neo8-audio-player.sh", "/tmp/neo8-agm/stream.wav", NULL};
        if (getuid()) { fprintf(stderr, "root recovery only\n"); return 64; }
        mkdir("/tmp/neo8-agm", 0755);
        int log = open("/tmp/neo8-audio-bridge.log", O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (log >= 0) { dup2(log, 1); dup2(log, 2); close(log); }
        return cmd_bridge(player[2], -1, player);
    }
    if (argc >= 5 && !strcmp(argv[1], "bridge")) {
        /* Test mode: --accept-uid N additionally admits that uid (e.g. a test runner). */
        int extra = argc > 5 && !strcmp(argv[3], "--accept-uid"), at = extra ? 5 : 3;
        if (argc > at + 1 && !strcmp(argv[at], "--")) return cmd_bridge(argv[2], extra ? atol(argv[4]) : -1, argv + at + 1);
    }
    if ((argc == 3 || argc == 4) && !strcmp(argv[1], "client")) {
        int channel = argc == 3 || !strcmp(argv[3], "both") ? 0 : !strcmp(argv[3], "left") ? 1 : !strcmp(argv[3], "right") ? 2 : -1;
        if (channel < 0) { fprintf(stderr, "client AMPLITUDE [both|left|right]\n"); return 64; }
        return cmd_client(atof(argv[2]), channel);
    }
    if (argc == 4 && !strcmp(argv[1], "gprping")) return cmd_gprping(argv[2], atoi(argv[3]));

    if (argc >= 3 && !strcmp(argv[1], "list")) return cmd_list(argv[2], argc > 3 ? argv[3] : NULL);
    if (argc == 5 && !strcmp(argv[1], "set")) return cmd_set(argv[2], argv[3], argv[4]);
    if (argc == 6 && !strcmp(argv[1], "tone")) return cmd_tone(argv[2], atof(argv[3]), atof(argv[4]), atof(argv[5]));
    fprintf(stderr, "usage: %s list CARD [FILTER] | set CARD NAME VALUE | tone FILE SECONDS HZ AMPLITUDE | gprping DEVICE MS\n", argv[0]);
    return 2;
}
