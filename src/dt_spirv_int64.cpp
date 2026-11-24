// Lowers 64-bit integer arithmetic in Daytona's SPIR-V to pairs of 32-bit
// integers, for GPUs without shaderInt64 (Adreno 7xx: the driver refuses to
// compile any of the game's shaders otherwise).
//
// XenosRecomp's shaders only use 64-bit integers to build buffer device
// addresses: a push-constant block of three uint64 base addresses, plus
// constant offsets, bitcast to PhysicalStorageBuffer pointers. SPIR-V allows a
// 64-bit pointer to be bitcast from a 2 x 32-bit integer vector, so the 64-bit
// type is re-declared as uvec2 (x = low word, y = high word: little-endian,
// the same bytes in memory) and each operation on it is rewritten:
//
//   OpConstant u64      -> OpConstantComposite uvec2(lo, hi)
//   OpIAdd              -> 32-bit add with carry
//   OpShiftLeftLogical  -> 64-bit shift built from 32-bit shifts and selects
//   OpBitwiseAnd/Or/Xor -> unchanged (component-wise on the vector)
//   OpUConvert u32->u64 -> uvec2(x, 0)
//   OpUConvert u64->u32 -> .x
//   OpIEqual/INotEqual  -> component-wise compare + OpAll/OpAny
//   OpLoad, OpBitcast to pointer, access chains -> unchanged
//
// Any other instruction touching the 64-bit type makes the pass refuse the
// module (the caller then keeps the original), rather than guess.

#include "dt_spirv_int64.h"

#include <cstring>
#include <map>
#include <set>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace dt_spirv_int64 {
namespace {

enum Op : uint32_t {
    OpExtInstImport = 11,
    OpTypeVoid = 19,
    OpTypeBool = 20,
    OpTypeInt = 21,
    OpTypeVector = 23,
    OpTypeStruct = 30,
    OpTypePointer = 32,
    OpConstantTrue = 41,
    OpConstant = 43,
    OpConstantComposite = 44,
    OpFunction = 54,
    OpVariable = 59,
    OpLoad = 61,
    OpAccessChain = 65,
    OpInBoundsAccessChain = 66,
    OpDecorate = 71,
    OpMemberDecorate = 72,
    OpCompositeConstruct = 80,
    OpCompositeExtract = 81,
    OpCompositeInsert = 82,
    OpVectorShuffle = 79,
    OpExtInst = 12,
    OpSource = 3,
    OpLine = 8,
    OpNoLine = 317,
    OpUConvert = 113,
    OpBitcast = 124,
    OpIAdd = 128,
    OpISub = 130,
    OpAny = 154,
    OpAll = 155,
    OpSelect = 169,
    OpIEqual = 170,
    OpINotEqual = 171,
    OpUGreaterThanEqual = 174,
    OpULessThan = 176,
    OpShiftRightLogical = 194,
    OpShiftLeftLogical = 196,
    OpBitwiseOr = 197,
    OpBitwiseXor = 198,
    OpBitwiseAnd = 199,
    OpCapability = 17,
    OpName = 5,
    OpMemberName = 6,
};

constexpr uint32_t kCapabilityInt64 = 11;

struct Inst {
    uint32_t op;
    std::vector<uint32_t> w;  // whole instruction, word 0 included
};

Inst Make(uint32_t op, std::initializer_list<uint32_t> operands) {
    Inst i;
    i.op = op;
    i.w.push_back(0);
    i.w.insert(i.w.end(), operands.begin(), operands.end());
    i.w[0] = (uint32_t(i.w.size()) << 16) | op;
    return i;
}

}  // namespace

bool Lower(std::vector<uint32_t>& words, std::string* error) {
    auto fail = [&](const std::string& why) {
        if (error) *error = why;
        return false;
    };
    if (words.size() < 5 || words[0] != 0x07230203) return fail("not SPIR-V");

    std::vector<Inst> insts;
    for (size_t i = 5; i < words.size();) {
        uint32_t count = words[i] >> 16;
        if (count == 0 || i + count > words.size()) return fail("truncated instruction");
        Inst in;
        in.op = words[i] & 0xFFFF;
        in.w.assign(words.begin() + i, words.begin() + i + count);
        insts.push_back(std::move(in));
        i += count;
    }
    uint32_t bound = words[3];
    auto fresh = [&]() { return bound++; };

    // Find the 64-bit integer type and the existing 32-bit / bool types.
    uint32_t u64 = 0, u32 = 0, tbool = 0, v2bool = 0;
    bool has_int64_cap = false;
    for (auto& in : insts) {
        if (in.op == OpCapability && in.w[1] == kCapabilityInt64) has_int64_cap = true;
        if (in.op == OpTypeInt && in.w[2] == 64) {
            if (in.w[3] != 0) return fail("signed 64-bit integers are not handled");
            if (u64) return fail("two 64-bit integer types");
            u64 = in.w[1];
        }
        if (in.op == OpTypeInt && in.w[2] == 32 && in.w[3] == 0) u32 = in.w[1];
        if (in.op == OpTypeBool) tbool = in.w[1];
    }
    if (!u64) {
        return has_int64_cap ? fail("Int64 capability without a 64-bit type") : true;
    }
    for (auto& in : insts) {
        if (in.op == OpTypeVector && tbool && in.w[2] == tbool && in.w[3] == 2) v2bool = in.w[1];
        if (in.op == OpTypeVector && in.w[2] == u64) return fail("vectors of 64-bit integers");
    }

    // Values of the 64-bit type: results typed u64.
    std::unordered_set<uint32_t> is64;
    // Result id -> type id, for instructions with a result type.
    for (auto& in : insts) {
        switch (in.op) {
            case OpConstant: case OpLoad: case OpIAdd: case OpISub: case OpBitwiseAnd:
            case OpBitwiseOr: case OpBitwiseXor: case OpShiftLeftLogical: case OpUConvert:
            case OpBitcast: case OpSelect: case OpCompositeExtract:
                if (in.w.size() > 2 && in.w[1] == u64) is64.insert(in.w[2]);
                break;
            default:
                break;
        }
    }

    // New ids the rewrite needs.
    const bool new_u32 = (u32 == 0);
    if (new_u32) u32 = fresh();
    const bool new_bool = (tbool == 0);
    if (new_bool) tbool = fresh();
    const bool new_v2bool = (v2bool == 0);
    if (new_v2bool) v2bool = fresh();

    std::map<uint32_t, uint32_t> u32_consts;  // value -> id
    auto c32 = [&](uint32_t v) {
        auto it = u32_consts.find(v);
        if (it != u32_consts.end()) return it->second;
        uint32_t id = fresh();
        u32_consts[v] = id;
        return id;
    };
    // Constants the expansions use, allocated up front.
    c32(0); c32(1); c32(31); c32(32); c32(63);

    std::vector<Inst> out;
    out.reserve(insts.size() * 2);
    bool placed_types = false;
    size_t first_function = insts.size();
    for (size_t i = 0; i < insts.size(); ++i) {
        if (insts[i].op == OpFunction) { first_function = i; break; }
    }

    // Instructions moved ahead of the u64 declaration (u32 / bool types that
    // were declared after it), so the redefinition can refer to them.
    std::set<size_t> moved;
    size_t u64_index = 0;
    for (size_t i = 0; i < insts.size(); ++i) {
        if (insts[i].op == OpTypeInt && insts[i].w[1] == u64) u64_index = i;
    }
    for (size_t i = u64_index + 1; i < first_function; ++i) {
        auto& in = insts[i];
        if ((in.op == OpTypeInt && in.w[1] == u32) || (in.op == OpTypeBool && in.w[1] == tbool) ||
            (in.op == OpTypeVector && in.w[1] == v2bool)) {
            moved.insert(i);
        }
    }

    std::vector<Inst> body;  // function-section expansion scratch
    for (size_t i = 0; i < insts.size(); ++i) {
        Inst& in = insts[i];
        if (moved.count(i)) continue;

        if (in.op == OpCapability && in.w[1] == kCapabilityInt64) continue;  // dropped

        if (i == u64_index) {
            // Types first: uint, bool, bvec2 (moved here or new), then u64 = uvec2.
            if (new_u32) out.push_back(Make(OpTypeInt, {u32, 32, 0}));
            if (new_bool) out.push_back(Make(OpTypeBool, {tbool}));
            for (size_t m : moved) out.push_back(insts[m]);
            if (new_v2bool) out.push_back(Make(OpTypeVector, {v2bool, tbool, 2}));
            out.push_back(Make(OpTypeVector, {u64, u32, 2}));
            placed_types = true;
            continue;
        }

        if (in.op == OpConstant && in.w[1] == u64) {
            if (in.w.size() != 5) return fail("malformed 64-bit constant");
            uint32_t lo = c32(in.w[3]), hi = c32(in.w[4]);
            // The uint constants are emitted right after the types (below),
            // before any of these composites.
            out.push_back(Make(OpConstantComposite, {u64, in.w[2], lo, hi}));
            continue;
        }

        // Inside functions: rewrite operations on 64-bit values.
        if (i > first_function || (i == first_function)) {
            auto ex = [&](uint32_t v, uint32_t idx) {
                uint32_t id = fresh();
                out.push_back(Make(OpCompositeExtract, {u32, id, v, idx}));
                return id;
            };
            auto bin = [&](uint32_t op, uint32_t type, uint32_t a, uint32_t b) {
                uint32_t id = fresh();
                out.push_back(Make(op, {type, id, a, b}));
                return id;
            };
            auto sel = [&](uint32_t type, uint32_t c, uint32_t a, uint32_t b) {
                uint32_t id = fresh();
                out.push_back(Make(OpSelect, {type, id, c, a, b}));
                return id;
            };
            const uint32_t rt = in.w.size() > 1 ? in.w[1] : 0;
            switch (in.op) {
                case OpIAdd:
                case OpISub:
                    if (rt == u64) {
                        uint32_t res = in.w[2], a = in.w[3], b = in.w[4];
                        uint32_t ax = ex(a, 0), ay = ex(a, 1), bx = ex(b, 0), by = ex(b, 1);
                        uint32_t lo, hi;
                        if (in.op == OpIAdd) {
                            lo = bin(OpIAdd, u32, ax, bx);
                            uint32_t carry = bin(OpULessThan, tbool, lo, ax);
                            uint32_t c = sel(u32, carry, c32(1), c32(0));
                            hi = bin(OpIAdd, u32, bin(OpIAdd, u32, ay, by), c);
                        } else {
                            lo = bin(OpISub, u32, ax, bx);
                            uint32_t borrow = bin(OpULessThan, tbool, ax, bx);
                            uint32_t c = sel(u32, borrow, c32(1), c32(0));
                            hi = bin(OpISub, u32, bin(OpISub, u32, ay, by), c);
                        }
                        out.push_back(Make(OpCompositeConstruct, {u64, res, lo, hi}));
                        continue;
                    }
                    break;
                case OpShiftLeftLogical:
                    if (rt == u64) {
                        uint32_t res = in.w[2], a = in.w[3], s = in.w[4];
                        uint32_t n = is64.count(s) ? ex(s, 0) : s;
                        n = bin(OpBitwiseAnd, u32, n, c32(63));
                        uint32_t ge = bin(OpUGreaterThanEqual, tbool, n, c32(32));
                        uint32_t nl = bin(OpBitwiseAnd, u32, n, c32(31));
                        uint32_t ax = ex(a, 0), ay = ex(a, 1);
                        uint32_t lo_s = bin(OpShiftLeftLogical, u32, ax, nl);
                        uint32_t hi_s = bin(OpShiftLeftLogical, u32, ay, nl);
                        // Bits carried from low to high; a shift by 32 is
                        // undefined, so the nl == 0 case is selected away.
                        uint32_t back = bin(OpISub, u32, c32(32), nl);
                        uint32_t carry = bin(OpShiftRightLogical, u32, ax, back);
                        uint32_t nz = bin(OpINotEqual, tbool, nl, c32(0));
                        uint32_t carry2 = sel(u32, nz, carry, c32(0));
                        uint32_t hi_lt = bin(OpBitwiseOr, u32, hi_s, carry2);
                        uint32_t lo = sel(u32, ge, c32(0), lo_s);
                        uint32_t hi = sel(u32, ge, lo_s, hi_lt);
                        out.push_back(Make(OpCompositeConstruct, {u64, res, lo, hi}));
                        continue;
                    }
                    if (in.w.size() > 4 && is64.count(in.w[3])) return fail("32-bit shift of a 64-bit value");
                    break;
                case OpUConvert:
                    if (rt == u64) {
                        out.push_back(Make(OpCompositeConstruct, {u64, in.w[2], in.w[3], c32(0)}));
                        continue;
                    }
                    if (is64.count(in.w[3])) {
                        out.push_back(Make(OpCompositeExtract, {rt, in.w[2], in.w[3], 0}));
                        continue;
                    }
                    break;
                case OpIEqual:
                case OpINotEqual:
                    if (is64.count(in.w[3]) || is64.count(in.w[4])) {
                        uint32_t cmp = bin(in.op, v2bool, in.w[3], in.w[4]);
                        out.push_back(Make(in.op == OpIEqual ? OpAll : OpAny, {rt, in.w[2], cmp}));
                        continue;
                    }
                    break;
                case OpBitwiseAnd:
                case OpBitwiseOr:
                case OpBitwiseXor:
                case OpLoad:
                case OpAccessChain:
                case OpInBoundsAccessChain:
                case OpSelect:
                    break;  // valid as-is on the vector type
                case OpBitcast:
                    // u64 -> pointer is fine on uvec2; anything else is not.
                    if (rt == u64 || (in.w.size() > 3 && is64.count(in.w[3]))) {
                        bool to_pointer = false;
                        for (auto& t : insts) {
                            if (t.op == OpTypePointer && t.w[1] == rt) { to_pointer = true; break; }
                        }
                        if (!to_pointer) return fail("bitcast of a 64-bit integer to a non-pointer");
                    }
                    break;
                default: {
                    // Anything else must not touch 64-bit values. Only id
                    // operands count: literals (an extended instruction's
                    // number, extract indices, shuffle components) can hold
                    // the same numbers as unrelated ids.
                    size_t last = in.w.size();
                    std::vector<size_t> skip;
                    switch (in.op) {
                        case OpExtInst: skip.push_back(3); skip.push_back(4); break;  // set, number
                        case OpCompositeExtract: last = 4; break;                     // composite only
                        case OpCompositeInsert: last = 5; break;                      // object, composite
                        case OpVectorShuffle: last = 5; break;                        // two vectors
                        case OpLine: case OpNoLine: case OpSource: case OpMemberDecorate:
                        case OpName: case OpMemberName: case OpDecorate:
                            last = 0; break;
                        default: break;
                    }
                    for (size_t k = 1; k < last; ++k) {
                        bool skipped = false;
                        for (size_t sk : skip) skipped |= (sk == k);
                        if (!skipped && is64.count(in.w[k])) {
                            return fail("unhandled opcode " + std::to_string(in.op) +
                                        " on a 64-bit value");
                        }
                    }
                    if (rt == u64 && in.op != OpFunction) {
                        return fail("unhandled opcode " + std::to_string(in.op) +
                                    " producing a 64-bit value");
                    }
                    break;
                }
            }
        }
        out.push_back(in);
    }
    if (!placed_types) return fail("64-bit type not placed");

    // The uint constants go right after the u64 redefinition (they only need
    // the uint type, and the u64 composites that use them come later).
    std::vector<Inst> consts;
    for (auto& [value, id] : u32_consts) consts.push_back(Make(OpConstant, {u32, id, value}));
    for (size_t i = 0; i < out.size(); ++i) {
        if (out[i].op == OpTypeVector && out[i].w[1] == u64) {
            out.insert(out.begin() + i + 1, consts.begin(), consts.end());
            break;
        }
    }

    std::vector<uint32_t> result(words.begin(), words.begin() + 5);
    result[3] = bound;
    for (auto& in : out) result.insert(result.end(), in.w.begin(), in.w.end());
    words.swap(result);
    return true;
}

bool UsesInt64(const uint32_t* words, size_t count) {
    for (size_t i = 5; i < count;) {
        uint32_t n = words[i] >> 16;
        if (n == 0) break;
        if ((words[i] & 0xFFFF) == OpCapability && i + 1 < count && words[i + 1] == kCapabilityInt64)
            return true;
        i += n;
    }
    return false;
}

}  // namespace dt_spirv_int64
