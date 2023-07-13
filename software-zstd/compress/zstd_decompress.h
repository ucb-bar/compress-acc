/*
 * Copyright (c) Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under both the BSD-style license (found in the
 * LICENSE file in the root directory of this source tree) and the GPLv2 (found
 * in the COPYING file in the root directory of this source tree).
 * You may select, at your option, one of the above-listed licenses.
 */

#include <stddef.h>   /* size_t */
#include <inttypes.h>   /* size_t */

typedef uint8_t  u8;
typedef uint32_t u32;

/******* EXPOSED TYPES ********************************************************/
/*
* Contains the parsed contents of a dictionary
* This includes Huffman and FSE tables used for decoding and data on offsets
*/
typedef struct dictionary_s dictionary_t;
/******* END EXPOSED TYPES ****************************************************/

/******* DECOMPRESSION FUNCTIONS **********************************************/
/// Zstandard decompression functions.
/// `dst` must point to a space at least as large as the reconstructed output.
size_t ZSTD_decompress(void *const dst, const size_t dst_len,
                    const void *const src, const size_t src_len);

/// If `dict != NULL` and `dict_len >= 8`, does the same thing as
/// `ZSTD_decompress` but uses the provided dict
size_t ZSTD_decompress_with_dict(void *const dst, const size_t dst_len,
                              const void *const src, const size_t src_len,
                              dictionary_t* parsed_dict);

/// Get the decompressed size of an input stream so memory can be allocated in
/// advance
/// Returns -1 if the size can't be determined
/// Assumes decompression of a single frame
size_t ZSTD_get_decompressed_size(const void *const src, const size_t src_len);
/******* END DECOMPRESSION FUNCTIONS ******************************************/

typedef struct {
    u32 literal_length;
    u32 match_length;
    u32 offset;
} sequence_command_t;

void standalone_execute_sequences(void *const dst, 
                                         const size_t dst_len,
                                         const u8 *const literals,
                                         const size_t literals_len,
                                         const sequence_command_t *const sequences,
                                         const size_t num_sequences,
                                         const size_t window_size);

size_t zstd_test_execute_sequences(void *const dst,
    const size_t dst_len,
    void *const src,
    const size_t src_len);

size_t accel_zstd_test_lz77_huf(
    void *const dst,
    const size_t dst_len,
    void *const src,
    const size_t src_len);

size_t accel_zstd_test_full(void *const dst,
                           const size_t dst_len,
                           void *const src,
                           const size_t src_len);


/******* DICTIONARY MANAGEMENT ***********************************************/
/*
 * Return a valid dictionary_t pointer for use with dictionary initialization
 * or decompression
 */
dictionary_t* create_dictionary(void);

/*
 * Parse a provided dictionary blob for use in decompression
 * `src` -- must point to memory space representing the dictionary
 * `src_len` -- must provide the dictionary size
 * `dict` -- will contain the parsed contents of the dictionary and
 *        can be used for decompression
 */
void parse_dictionary(dictionary_t *const dict, const void *src,
                             size_t src_len);

/*
 * Free internal Huffman tables, FSE tables, and dictionary content
 */
void free_dictionary(dictionary_t *const dict);
/******* END DICTIONARY MANAGEMENT *******************************************/
