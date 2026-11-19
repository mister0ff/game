// StarCat Launcher - libgxcore.so (open implementation).
//
// Written from the Java contract in org.levimc.launcher.util.NativeBridgeHelper:
//   nativeScanImage(path)            -> does this native library need to be rewritten?
//   nativeRewriteImage(in, out)      -> produce the prepared copy of a native library
//   nativeBootstrapGxCore()          -> one-time native setup
//   nativeLoadMinecraftRuntime(path) -> load libminecraftpe.so with its dependency graph;
//                                       returns null on success, an error message otherwise
//
// What this build does:
//   * scan:      reports that no rewrite is needed (libraries are used as extracted).
//   * rewrite:   validates the ELF64 file and copies it (in-place requests are only validated).
//   * bootstrap: nothing to set up.
//   * runtime:   reads DT_NEEDED from the ELF, dlopen()s the dependencies that sit next to the
//                library first (in order), then the library itself, all RTLD_NOW | RTLD_GLOBAL.
// No third-party or closed code is used.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <set>
#include <string>
#include <vector>

#define TAG "StarCatGxCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Mapped {
    void* data = nullptr;
    size_t size = 0;
    Mapped() = default;
    Mapped(const Mapped&) = delete;
    Mapped& operator=(const Mapped&) = delete;
    ~Mapped() { if (data) munmap(data, size); }
};

bool mapFile(const std::string& path, Mapped& out) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t) sizeof(Elf64_Ehdr)) { close(fd); return false; }
    void* p = mmap(nullptr, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (p == MAP_FAILED) return false;
    out.data = p;
    out.size = (size_t) st.st_size;
    return true;
}

bool isElf64(const Mapped& m) {
    auto* h = static_cast<const Elf64_Ehdr*>(m.data);
    return memcmp(h->e_ident, ELFMAG, SELFMAG) == 0 && h->e_ident[EI_CLASS] == ELFCLASS64;
}

// Collects the DT_NEEDED names of an ELF64 shared object.
bool readNeeded(const Mapped& m, std::vector<std::string>& out) {
    auto* base = static_cast<const uint8_t*>(m.data);
    auto* eh = reinterpret_cast<const Elf64_Ehdr*>(base);
    if (eh->e_phoff == 0 || eh->e_phentsize != sizeof(Elf64_Phdr)) return false;
    if (eh->e_phoff + (uint64_t) eh->e_phnum * sizeof(Elf64_Phdr) > m.size) return false;
    auto* ph = reinterpret_cast<const Elf64_Phdr*>(base + eh->e_phoff);

    const Elf64_Phdr* dyn = nullptr;
    for (int i = 0; i < eh->e_phnum; i++) if (ph[i].p_type == PT_DYNAMIC) dyn = &ph[i];
    if (!dyn) return true;
    if (dyn->p_offset + dyn->p_filesz > m.size) return false;

    auto vaddrToOffset = [&](uint64_t va, uint64_t& off) {
        for (int i = 0; i < eh->e_phnum; i++) {
            if (ph[i].p_type != PT_LOAD) continue;
            if (va >= ph[i].p_vaddr && va < ph[i].p_vaddr + ph[i].p_filesz) {
                off = va - ph[i].p_vaddr + ph[i].p_offset;
                return off < m.size;
            }
        }
        return false;
    };

    auto* d = reinterpret_cast<const Elf64_Dyn*>(base + dyn->p_offset);
    size_t count = dyn->p_filesz / sizeof(Elf64_Dyn);
    uint64_t strtab = 0;
    std::vector<uint64_t> names;
    for (size_t i = 0; i < count && d[i].d_tag != DT_NULL; i++) {
        if (d[i].d_tag == DT_STRTAB) strtab = d[i].d_un.d_ptr;
        else if (d[i].d_tag == DT_NEEDED) names.push_back(d[i].d_un.d_val);
    }
    if (names.empty()) return true;
    uint64_t strOff = 0;
    if (!vaddrToOffset(strtab, strOff)) return false;
    for (uint64_t n : names) {
        if (strOff + n >= m.size) continue;
        const char* s = reinterpret_cast<const char*>(base + strOff + n);
        out.emplace_back(s, strnlen(s, m.size - (strOff + n)));
    }
    return true;
}

bool fileExists(const std::string& p) {
    struct stat st;
    return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool loadGraph(const std::string& dir, const std::string& path,
               std::set<std::string>& seen, std::string& err) {
    if (!seen.insert(path).second) return true;

    std::vector<std::string> needed;
    {
        Mapped m;
        if (!mapFile(path, m)) { err = "cannot read " + path + ": " + strerror(errno); return false; }
        if (!isElf64(m) || !readNeeded(m, needed)) { err = "not a valid ELF64 library: " + path; return false; }
    }
    for (const std::string& dep : needed) {
        if (dep == "libc++_shared.so") continue;          // the launcher's copy is already loaded
        std::string depPath = dir + "/" + dep;
        if (!fileExists(depPath)) continue;                // system library: the linker resolves it
        if (!loadGraph(dir, depPath, seen, err)) return false;
    }
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char* e = dlerror();
        err = std::string("dlopen failed for ") + path + ": " + (e ? e : "unknown error");
        return false;
    }
    LOGI("loaded %s", path.c_str());
    return true;
}

bool copyFile(const std::string& from, const std::string& to) {
    int in = open(from.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) return false;
    int out = open(to.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (out < 0) { close(in); return false; }
    char buf[64 * 1024];
    bool ok = true;
    for (;;) {
        ssize_t n = read(in, buf, sizeof(buf));
        if (n < 0) { if (errno == EINTR) continue; ok = false; break; }
        if (n == 0) break;
        for (ssize_t off = 0; off < n;) {
            ssize_t w = write(out, buf + off, (size_t) (n - off));
            if (w < 0) { if (errno == EINTR) continue; ok = false; break; }
            off += w;
        }
        if (!ok) break;
    }
    if (ok && fsync(out) != 0) ok = false;
    close(in);
    close(out);
    return ok;
}

std::string toStd(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return std::string();
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

jboolean nativeScanImage(JNIEnv*, jclass, jstring) {
    return JNI_FALSE;  // nothing needs rewriting
}

jboolean nativeRewriteImage(JNIEnv* env, jclass, jstring jin, jstring jout) {
    std::string in = toStd(env, jin), out = toStd(env, jout);
    if (in.empty() || out.empty()) return JNI_FALSE;
    {
        Mapped m;
        if (!mapFile(in, m) || !isElf64(m)) { LOGE("rewrite: not an ELF64 file: %s", in.c_str()); return JNI_FALSE; }
    }
    if (in == out) return JNI_TRUE;
    if (!copyFile(in, out)) { LOGE("rewrite: copy failed %s -> %s", in.c_str(), out.c_str()); return JNI_FALSE; }
    return JNI_TRUE;
}

void nativeBootstrapGxCore(JNIEnv*, jclass) {}

jstring nativeLoadMinecraftRuntime(JNIEnv* env, jclass, jstring jpath) {
    std::string path = toStd(env, jpath);
    if (path.empty()) return env->NewStringUTF("empty library path");
    size_t slash = path.find_last_of('/');
    std::string dir = slash == std::string::npos ? "." : path.substr(0, slash);
    std::set<std::string> seen;
    std::string err;
    if (loadGraph(dir, path, seen, err)) return nullptr;
    LOGE("%s", err.c_str());
    return env->NewStringUTF(err.c_str());
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    jclass cls = env->FindClass("org/levimc/launcher/util/NativeBridgeHelper");
    if (!cls) { LOGE("NativeBridgeHelper class not found"); return JNI_ERR; }
    const JNINativeMethod methods[] = {
        {"nativeScanImage", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(nativeScanImage)},
        {"nativeRewriteImage", "(Ljava/lang/String;Ljava/lang/String;)Z", reinterpret_cast<void*>(nativeRewriteImage)},
        {"nativeBootstrapGxCore", "()V", reinterpret_cast<void*>(nativeBootstrapGxCore)},
        {"nativeLoadMinecraftRuntime", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(nativeLoadMinecraftRuntime)},
    };
    if (env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE("RegisterNatives failed");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
