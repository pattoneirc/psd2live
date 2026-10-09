/*
 * Plays a .p2lrt rig for a second and draws it with the runtime's software renderer into a BMP: the C ABI from C or
 * C++, with no renderer of the host's own. MIT License.
 *
 *   render_example model.p2lrt frame.bmp [width]
 */
#define _CRT_SECURE_NO_WARNINGS /* fopen, as portable C has it */
#include "p2l_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *bytes = (unsigned char *)malloc(n > 0 ? (size_t)n : 1);
    *len = bytes ? fread(bytes, 1, (size_t)n, f) : 0;
    fclose(f);
    return bytes;
}

static void put32(FILE *f, uint32_t v) {
    unsigned char b[4] = {(unsigned char)v, (unsigned char)(v >> 8), (unsigned char)(v >> 16), (unsigned char)(v >> 24)};
    fwrite(b, 1, 4, f);
}

/* A 32-bit BMP of straight RGBA [rgba], rows top first. */
static int write_bmp(const char *path, const uint8_t *rgba, uint32_t width, uint32_t height) {
    FILE *f = fopen(path, "wb");
    if (!f) return 0;
    uint32_t size = width * height * 4;
    fwrite("BM", 1, 2, f);
    put32(f, 54 + 68 + size); put32(f, 0); put32(f, 54 + 68);
    /* BITMAPV4HEADER with alpha: width, a negative height for rows top first, 32 bits, BI_BITFIELDS. */
    put32(f, 108); put32(f, width); put32(f, (uint32_t)-(int32_t)height);
    fputc(1, f); fputc(0, f); fputc(32, f); fputc(0, f);
    put32(f, 3); put32(f, size); put32(f, 2835); put32(f, 2835); put32(f, 0); put32(f, 0);
    put32(f, 0x00FF0000u); put32(f, 0x0000FF00u); put32(f, 0x000000FFu); put32(f, 0xFF000000u);
    fwrite("BGRs", 1, 4, f);
    for (int i = 0; i < 12; i++) put32(f, 0);
    for (uint32_t i = 0; i < width * height; i++) {
        unsigned char px[4] = {rgba[i * 4 + 2], rgba[i * 4 + 1], rgba[i * 4], rgba[i * 4 + 3]};
        fwrite(px, 1, 4, f);
    }
    fclose(f);
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s model.p2lrt frame.bmp [width]\n", argv[0]);
        return 2;
    }
    uint32_t abi = p2l_abi_version();
    if (!P2L_ABI_COMPATIBLE(abi)) {
        fprintf(stderr, "runtime ABI %u.%u, built against %u.%u\n", abi >> 16, abi & 0xffffu, P2L_ABI_VERSION_MAJOR, P2L_ABI_VERSION_MINOR);
        return 1;
    }
    size_t len = 0;
    unsigned char *bytes = read_file(argv[1], &len);
    if (!bytes) {
        fprintf(stderr, "cannot read %s\n", argv[1]);
        return 1;
    }
    char error[256] = {0};
    P2lModel *model = p2l_model_load(bytes, len, P2L_LOAD_VERIFY_CRC, error, sizeof error);
    free(bytes);
    if (!model) {
        fprintf(stderr, "cannot load: %s\n", error);
        return 1;
    }
    P2lRig *rig = p2l_rig_create(model, error, sizeof error);
    p2l_model_free(model); /* the rig keeps it */
    if (!rig) {
        fprintf(stderr, "cannot create: %s\n", error);
        return 1;
    }
    printf("runtime %s, ABI %u.%u: %u parameters, %u meshes, %u parts, %u clips\n", p2l_version(), abi >> 16, abi & 0xffffu,
           p2l_parameter_count(rig), p2l_mesh_count(rig), p2l_part_count(rig), p2l_clip_count(rig));
    if (p2l_clip_count(rig) > 0) p2l_play(rig, 0);
    p2l_physics_stabilize(rig);
    for (int i = 0; i < 60; i++) {
        p2l_update(rig, 1.0f / 60.0f);
        for (uint32_t e = 0; e < p2l_event_count(rig); e++) printf("event: %s\n", p2l_event(rig, e, NULL, NULL, NULL));
    }
    float cw = 0, ch = 0;
    p2l_canvas(rig, &cw, &ch);
    uint32_t width = argc > 3 ? (uint32_t)atoi(argv[3]) : 512;
    uint32_t height = (uint32_t)(width * ch / cw + 0.5f);
    uint8_t *rgba = (uint8_t *)calloc((size_t)width * height, 4);
    int ok = rgba && p2l_render(rig, rgba, width, height, NULL, P2L_RENDER_STRAIGHT);
    const char *failure = p2l_rig_failure(rig);
    if (failure) fprintf(stderr, "the runtime failed: %s\n", failure);
    if (ok) ok = write_bmp(argv[2], rgba, width, height);
    uint32_t covered = 0;
    for (uint32_t i = 0; rgba && i < width * height; i++) covered += rgba[i * 4 + 3] != 0;
    printf("%s %ux%u, %u pixels drawn\n", ok ? "wrote" : "could not write", width, height, covered);
    free(rgba);
    p2l_rig_free(rig);
    return ok && !failure ? 0 : 1;
}
