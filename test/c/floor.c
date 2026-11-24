/* The C floor for glimmer-cui.bench: the same rows UI submitted straight from C
 * on a bare context with the software renderer attached. Whatever Jolt adds
 * on top of this is the binding's cost.
 * Run: jolt floor */
#include <cui/cui.h>
#include <cui/cui_render.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static double now_us(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e6 + t.tv_nsec / 1e3;
}
static int cmp(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

static void frame(cui_ctx *ui, int n, bool *on, int *changes) {
    char buf[64];
    cui_column_begin(ui, &(cui_box){ .pad = 16, .gap = 6, .width = CUI_FILL });
    cui_heading(ui, "Devices", 3);
    snprintf(buf, sizeof buf, "%d devices, %d changes", n, *changes);
    cui_secondary(ui, buf);
    for (int i = 0; i < n; i++) {
        cui_push_id_int(ui, i);
        cui_row_begin(ui, &(cui_box){ .gap = 8, .width = CUI_FILL });
        snprintf(buf, sizeof buf, "Device %d", i);
        cui_text_ex(ui, buf, &(cui_text_opts){0});
        cui_spacer(ui);
        cui_badge_ex(ui, on[i] ? "online" : "offline", &(cui_badge_opts){ .tone = on[i] ? CUI_TONE_SUCCESS : CUI_TONE_NEUTRAL });
        cui_switch_ex(ui, "Enabled", &on[i], &(cui_check_opts){0});
        if (cui_button_ex(ui, "Details", &(cui_button_opts){ .size = CUI_SIZE_SM })) (*changes)++;
        cui_box_end(ui);
        cui_pop_id(ui);
    }
    cui_box_end(ui);
}

int main(int argc, char **argv) {
    int sizes[] = { 10, 50, 200 };
    for (int s = 0; s < 3; s++) {
        int n = sizes[s];
        cui_sw_renderer *sw = cui_sw_create();
        cui_sw_set_threads(sw, 1);
        cui_ctx *ui = cui_create(&(cui_ctx_desc){ .renderer = cui_sw_iface(sw) });
        cui_set_viewport(ui, 640, 900, 1);
        bool *on = calloc(n, 1); for (int i = 0; i < n; i++) on[i] = true;
        int changes = 0, warm = 60, frames = 300;
        double *t = malloc(sizeof(double) * frames), sum = 0;
        for (int i = 0; i < warm + frames; i++) {
            double t0 = now_us();
            cui_begin_frame(ui, 1.0f / 60);
            frame(ui, n, on, &changes);
            cui_end_frame(ui);
            if (i >= warm) { t[i - warm] = now_us() - t0; sum += t[i - warm]; }
        }
        qsort(t, frames, sizeof *t, cmp);
        printf("%3d rows: mean %8.1f  p50 %8.1f  p95 %8.1f us\n", n, sum / frames, t[frames / 2], t[(int)(frames * 0.95)]);
        cui_destroy(ui); cui_sw_destroy(sw); free(on); free(t);
    }
    (void)argc; (void)argv;
    return 0;
}
