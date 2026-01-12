#pragma once
#include <stdint.h>

typedef struct r36dec_ctx r36dec_ctx;

r36dec_ctx* r36dec_create(int input_sr);
void r36dec_destroy(r36dec_ctx* ctx);
void r36dec_feed(r36dec_ctx* ctx, const float* samples, int n, int input_sr);
int r36dec_get_line_index(r36dec_ctx* ctx);
const char* r36dec_get_state(r36dec_ctx* ctx);
int r36dec_is_completed(r36dec_ctx* ctx);
uint32_t* r36dec_get_argb(r36dec_ctx* ctx);