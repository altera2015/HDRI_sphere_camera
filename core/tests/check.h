// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal test harness: no external dependencies so the core builds anywhere.
#pragma once
#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace hdri_test {
struct Case { std::string name; std::function<void()> fn; };
inline std::vector<Case>& registry() { static std::vector<Case> r; return r; }
inline int& failures() { static int f = 0; return f; }
struct Registrar { Registrar(const char* n, std::function<void()> f) { registry().push_back({n, std::move(f)}); } };
}  // namespace hdri_test

#define TEST_CASE(name) \
    static void name(); \
    static hdri_test::Registrar name##_reg(#name, name); \
    static void name()

#define CHECK(cond) \
    do { if (!(cond)) { std::fprintf(stderr, "  %s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); ++hdri_test::failures(); } } while (0)
