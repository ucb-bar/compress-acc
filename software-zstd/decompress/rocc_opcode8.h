// Based on code by Schuyler Eldridge. Copyright (c) Boston University
// https://github.com/seldridge/rocket-rocc-examples/blob/master/src/main/c/rocc.h

#ifndef SRC_MAIN_C_ROCC_H
#define SRC_MAIN_C_ROCC_H

// Standard macro that passes rd, rs1, and rs2 via registers
#define ROCC_INSTRUCTION_DSS(X, rd, rs1, rs2, funct) \
    ROCC_INSTRUCTION_R_R_R(X/2, rd, rs1, rs2, 64*(X%2)+funct)

#define ROCC_INSTRUCTION_DS(X, rd, rs1, funct) \
    ROCC_INSTRUCTION_R_R_I(X/2, rd, rs1, 0, 64*(X%2)+funct)

#define ROCC_INSTRUCTION_D(X, rd, funct) \
    ROCC_INSTRUCTION_R_I_I(X/2, rd, 0, 0, 64*(X%2)+funct)

#define ROCC_INSTRUCTION_SS(X, rs1, rs2, funct) \
    ROCC_INSTRUCTION_I_R_R(X/2, 0, rs1, rs2, 64*(X%2)+funct)

#define ROCC_INSTRUCTION_S(X, rs1, funct) \
    ROCC_INSTRUCTION_I_R_I(X/2, 0, rs1, 0, 64*(X%2)+funct)

#define ROCC_INSTRUCTION(X, funct) \
    ROCC_INSTRUCTION_I_I_I(X/2, 0, 0, 0, 64*(X%2)+funct)

// funct3 flags
#define ROCC_XD     0x4
#define ROCC_XS1    0x2
#define ROCC_XS2    0x1

// rd must be an lvalue if used as a register.
// These macros do not insert a compiler memory barrier.

#define ROCC_INSTRUCTION_R_R_R(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_%1, %4, %5, %0, %2, %3\n\t"             \
            : "=r" (rd)                                                 \
            : "i" (X), "r" (rs1), "r" (rs2),                                     \
              "i" (ROCC_XD | ROCC_XS1 | ROCC_XS2), "i" (funct));        \
    } while (0)

#define ROCC_INSTRUCTION_R_R_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_%1, %4, %5, %0, %2, x%3\n\t"            \
            : "=r" (rd)                                                 \
            : "i" (X), "r" (rs1), "K" (rs2),                                     \
              "i" (ROCC_XD | ROCC_XS1), "i" (funct));                   \
    } while (0)

#define ROCC_INSTRUCTION_R_I_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_%1, %4, %5, %0, x%2, x%3\n\t"           \
            : "=r" (rd)                                                 \
            : "i" (X), "K" (rs1), "K" (rs2),                                     \
              "i" (ROCC_XD), "i" (funct));                              \
    } while (0)

#define ROCC_INSTRUCTION_I_R_R(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_%0, %4, %5, x%1, %2, %3\n\t"            \
            :                                                           \
            : "i" (X), "K" (rd), "r" (rs1), "r" (rs2),                           \
              "i" (ROCC_XS1 | ROCC_XS2), "i" (funct));                  \
    } while (0)

#define ROCC_INSTRUCTION_I_R_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_%0, %4, %5, x%1, %2, x%3\n\t"           \
            :                                                           \
            : "i" (X), "K" (rd), "r" (rs1), "K" (rs2),                           \
              "i" (ROCC_XS1), "i" (funct));                             \
    } while (0)

#define ROCC_INSTRUCTION_I_I_I(X, rd, rs1, rs2, funct)                  \
    do {                                                                \
        __asm__ __volatile__ (                                          \
            ".insn r CUSTOM_%0, %4, %5, x%1, x%2, x%3\n\t"          \
            :                                                           \
            : "i" (X), "K" (rd), "K" (rs1), "K" (rs2),                           \
              "i" (0), "i" (funct));                                    \
    } while (0)

#endif // SRC_MAIN_C_ROCC_H
