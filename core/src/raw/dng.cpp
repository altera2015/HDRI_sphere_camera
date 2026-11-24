// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal TIFF/DNG reader: enough for the uncompressed 16-bit CFA images Android's DngCreator writes.
#include <algorithm>
#include <cstdio>
#include <cstring>

#include "hdri/raw.h"

namespace hdri {
namespace {

struct File {
    FILE* f = nullptr;
    bool le = true;
    ~File() { if (f) std::fclose(f); }
    bool seek(uint64_t off) { return std::fseek(f, static_cast<long>(off), SEEK_SET) == 0; }
    bool read(void* dst, size_t n) { return std::fread(dst, 1, n, f) == n; }
    uint16_t u16() {
        uint8_t b[2] = {0, 0};
        read(b, 2);
        return le ? static_cast<uint16_t>(b[0] | (b[1] << 8)) : static_cast<uint16_t>((b[0] << 8) | b[1]);
    }
    uint32_t u32() {
        uint8_t b[4] = {0, 0, 0, 0};
        read(b, 4);
        return le ? (b[0] | (b[1] << 8) | (b[2] << 16) | (static_cast<uint32_t>(b[3]) << 24))
                  : ((static_cast<uint32_t>(b[0]) << 24) | (b[1] << 16) | (b[2] << 8) | b[3]);
    }
};

struct Ifd {
    uint32_t newSubfileType = 0, width = 0, height = 0, bps = 0, compression = 1, photometric = 0, spp = 1;
    std::vector<uint32_t> stripOffsets, stripCounts, subIfds;
};

// Reads the values of one IFD entry (SHORT or LONG/IFD) as uint32s; restores the file position.
std::vector<uint32_t> entryValues(File& f, uint16_t type, uint32_t count, long valuePos) {
    std::vector<uint32_t> v;
    const size_t size = (type == 3 ? 2 : 4) * static_cast<size_t>(count);
    const long cur = std::ftell(f.f);
    std::fseek(f.f, valuePos, SEEK_SET);
    if (size > 4) f.seek(f.u32());
    for (uint32_t i = 0; i < count && i < (1u << 20); ++i) v.push_back(type == 3 ? f.u16() : f.u32());
    std::fseek(f.f, cur, SEEK_SET);
    return v;
}

bool readIfd(File& f, uint32_t offset, Ifd& ifd, uint32_t* next) {
    if (!f.seek(offset)) return false;
    const uint16_t n = f.u16();
    for (uint16_t i = 0; i < n; ++i) {
        const long entryPos = std::ftell(f.f);
        const uint16_t tag = f.u16(), type = f.u16();
        const uint32_t count = f.u32();
        const long valuePos = std::ftell(f.f);
        std::fseek(f.f, entryPos + 12, SEEK_SET);
        if (type != 3 && type != 4 && type != 13) continue;
        auto vals = [&] { return entryValues(f, type, count, valuePos); };
        auto one = [&] { auto v = vals(); return v.empty() ? 0u : v[0]; };
        switch (tag) {
            case 254: ifd.newSubfileType = one(); break;
            case 256: ifd.width = one(); break;
            case 257: ifd.height = one(); break;
            case 258: ifd.bps = one(); break;
            case 259: ifd.compression = one(); break;
            case 262: ifd.photometric = one(); break;
            case 273: ifd.stripOffsets = vals(); break;
            case 277: ifd.spp = one(); break;
            case 279: ifd.stripCounts = vals(); break;
            case 330: ifd.subIfds = vals(); break;
            default: break;
        }
    }
    if (next) *next = f.u32();
    return true;
}

}  // namespace

bool readDngRaw(const std::string& path, RawImage& out, std::string* error) {
    auto fail = [&](const char* msg) { if (error) *error = msg; return false; };
    File f;
    f.f = std::fopen(path.c_str(), "rb");
    if (!f.f) return fail("cannot open file");
    char hdr[4];
    if (!f.read(hdr, 4)) return fail("short file");
    if (hdr[0] == 'I' && hdr[1] == 'I') f.le = true;
    else if (hdr[0] == 'M' && hdr[1] == 'M') f.le = false;
    else return fail("not a TIFF/DNG");
    const uint32_t first = f.u32();

    // Walk the IFD chain and SubIFDs looking for the full-resolution CFA image.
    std::vector<uint32_t> queue{first};
    Ifd found;
    bool ok = false;
    for (size_t qi = 0; qi < queue.size() && qi < 64; ++qi) {
        Ifd ifd;
        uint32_t next = 0;
        if (!readIfd(f, queue[qi], ifd, &next)) continue;
        if (ifd.photometric == 32803 && (ifd.newSubfileType & 1) == 0) { found = ifd; ok = true; break; }
        for (uint32_t s : ifd.subIfds) queue.push_back(s);
        if (next) queue.push_back(next);
    }
    if (!ok) return fail("no CFA image in DNG");
    if (found.compression != 1) return fail("compressed DNG not supported");
    if (found.bps != 16 || found.spp != 1) return fail("only 16-bit single-sample CFA supported");
    if (found.stripOffsets.size() != found.stripCounts.size() || found.stripOffsets.empty()) return fail("bad strips");

    out.width = static_cast<int>(found.width);
    out.height = static_cast<int>(found.height);
    out.data.assign(static_cast<size_t>(out.width) * out.height, 0);
    const size_t total = out.data.size() * 2;
    size_t pos = 0;
    auto* dst = reinterpret_cast<uint8_t*>(out.data.data());
    for (size_t i = 0; i < found.stripOffsets.size() && pos < total; ++i) {
        const size_t n = std::min<size_t>(found.stripCounts[i], total - pos);
        if (!f.seek(found.stripOffsets[i]) || !f.read(dst + pos, n)) return fail("truncated strip");
        pos += n;
    }
    if (pos != total) return fail("image data incomplete");
    if (!f.le) {
        for (auto& v : out.data) v = static_cast<uint16_t>((v >> 8) | (v << 8));
    }
    return true;
}

}  // namespace hdri
