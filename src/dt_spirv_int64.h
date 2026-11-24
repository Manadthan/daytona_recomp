// Lowers 64-bit integer arithmetic in SPIR-V to 32-bit pairs, for GPUs that
// lack shaderInt64 (Adreno). See dt_spirv_int64.cpp.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace dt_spirv_int64 {

// True when the module declares the Int64 capability.
bool UsesInt64(const uint32_t* words, size_t count);

// Rewrites the module in place. Returns false (module untouched, reason in
// *error) when it contains a 64-bit operation the pass does not handle.
bool Lower(std::vector<uint32_t>& words, std::string* error);

}  // namespace dt_spirv_int64
