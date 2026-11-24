// SPDX-License-Identifier: GPL-3.0-or-later
#include "check.h"

int main() {
    int failedCases = 0;
    for (auto& c : hdri_test::registry()) {
        int before = hdri_test::failures();
        c.fn();
        bool ok = hdri_test::failures() == before;
        std::printf("[%s] %s\n", ok ? " OK " : "FAIL", c.name.c_str());
        if (!ok) ++failedCases;
    }
    std::printf("%zu cases, %d failed\n", hdri_test::registry().size(), failedCases);
    return failedCases == 0 ? 0 : 1;
}
