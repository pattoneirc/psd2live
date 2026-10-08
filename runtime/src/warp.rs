//! The warp mapping from a lattice's normalized space to its points' space.
//!
//! Inside the unit square each cell interpolates bilinearly, or as two triangles split along the
//! diagonal from (1, 0) to (0, 1). Outside it the lattice extends over [-2, 3]² through a coarser
//! virtual grid whose extra points follow the lattice's mean affine frame, always interpolated as
//! triangles; beyond that, points follow the affine frame alone.

/// The mean affine frame of a lattice: its corners' centre and the averaged edge vectors.
struct Frame {
    c: [f64; 2],
    ex: [f64; 2],
    ey: [f64; 2],
}

impl Frame {
    fn new(points: &[f32], columns: usize, rows: usize) -> Frame {
        let p = |c: usize, r: usize| {
            let i = (r * (columns + 1) + c) * 2;
            [points[i] as f64, points[i + 1] as f64]
        };
        let (c00, c10, c01, c11) = (p(0, 0), p(columns, 0), p(0, rows), p(columns, rows));
        let mut frame = Frame { c: [0.0; 2], ex: [0.0; 2], ey: [0.0; 2] };
        for k in 0..2 {
            frame.c[k] = (c00[k] + c10[k] + c01[k] + c11[k]) / 4.0;
            frame.ex[k] = ((c10[k] - c00[k]) + (c11[k] - c01[k])) / 2.0;
            frame.ey[k] = ((c01[k] - c00[k]) + (c11[k] - c10[k])) / 2.0;
        }
        frame
    }

    fn at(&self, u: f64, v: f64) -> [f64; 2] {
        [
            self.c[0] + (u - 0.5) * self.ex[0] + (v - 0.5) * self.ey[0],
            self.c[1] + (u - 0.5) * self.ex[1] + (v - 0.5) * self.ey[1],
        ]
    }
}

fn triangle(q00: [f64; 2], q10: [f64; 2], q01: [f64; 2], q11: [f64; 2], s: f64, t: f64) -> [f64; 2] {
    if s + t <= 1.0 {
        [q00[0] + s * (q10[0] - q00[0]) + t * (q01[0] - q00[0]), q00[1] + s * (q10[1] - q00[1]) + t * (q01[1] - q00[1])]
    } else {
        [
            q11[0] + (1.0 - s) * (q01[0] - q11[0]) + (1.0 - t) * (q10[0] - q11[0]),
            q11[1] + (1.0 - s) * (q01[1] - q11[1]) + (1.0 - t) * (q10[1] - q11[1]),
        ]
    }
}

fn bilinear(q00: [f64; 2], q10: [f64; 2], q01: [f64; 2], q11: [f64; 2], s: f64, t: f64) -> [f64; 2] {
    let mut out = [0.0; 2];
    for k in 0..2 {
        out[k] = (1.0 - s) * (1.0 - t) * q00[k] + s * (1.0 - t) * q10[k] + (1.0 - s) * t * q01[k] + s * t * q11[k];
    }
    out
}

/// A lattice ready to map points: its control points in the target space.
pub struct Lattice<'a> {
    pub points: &'a [f32],
    pub columns: usize,
    pub rows: usize,
    pub bilinear: bool,
}

impl Lattice<'_> {
    fn point(&self, c: usize, r: usize) -> [f64; 2] {
        let i = (r * (self.columns + 1) + c) * 2;
        [self.points[i] as f64, self.points[i + 1] as f64]
    }

    pub fn map(&self, u: f32, v: f32) -> [f32; 2] {
        let p = self.map64(u as f64, v as f64);
        [p[0] as f32, p[1] as f32]
    }

    /// [map] without narrowing the result, for measurements between nearby points.
    pub fn map64(&self, u: f64, v: f64) -> [f64; 2] {
        let (columns, rows) = (self.columns, self.rows);
        if (0.0..=1.0).contains(&u) && (0.0..=1.0).contains(&v) {
            let fx = u * columns as f64;
            let fy = v * rows as f64;
            let i = (fx as usize).min(columns - 1);
            let j = (fy as usize).min(rows - 1);
            let (s, t) = (fx - i as f64, fy - j as f64);
            let q = (self.point(i, j), self.point(i + 1, j), self.point(i, j + 1), self.point(i + 1, j + 1));
            return if self.bilinear { bilinear(q.0, q.1, q.2, q.3, s, t) } else { triangle(q.0, q.1, q.2, q.3, s, t) };
        }
        let frame = Frame::new(self.points, columns, rows);
        if !(-2.0..=3.0).contains(&u) || !(-2.0..=3.0).contains(&v) {
            return frame.at(u, v);
        }
        // Virtual grid lines: -2, the lattice's own lines, then 3.
        let line = |i: usize, n: usize| -> f64 {
            if i == 0 {
                -2.0
            } else if i == n + 2 {
                3.0
            } else {
                (i - 1) as f64 / n as f64
            }
        };
        let cell = |x: f64, n: usize| -> usize {
            if x >= 3.0 {
                return n + 1;
            }
            let mut i = 0;
            while i + 1 < n + 2 && line(i + 1, n) <= x {
                i += 1;
            }
            i
        };
        let (ci, ri) = (cell(u, columns), cell(v, rows));
        let virtual_point = |c: usize, r: usize| -> [f64; 2] {
            if (1..=columns + 1).contains(&c) && (1..=rows + 1).contains(&r) {
                self.point(c - 1, r - 1)
            } else {
                frame.at(line(c, columns), line(r, rows))
            }
        };
        let (u0, u1) = (line(ci, columns), line(ci + 1, columns));
        let (v0, v1) = (line(ri, rows), line(ri + 1, rows));
        let s = (u - u0) / (u1 - u0);
        let t = (v - v0) / (v1 - v0);
        triangle(virtual_point(ci, ri), virtual_point(ci + 1, ri), virtual_point(ci, ri + 1), virtual_point(ci + 1, ri + 1), s, t)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_affine_lattice_maps_affinely_everywhere() {
        // A 2x1 lattice of an affine map: scale 100 x 50, sheared, offset (10, 20).
        let f = |u: f32, v: f32| [10.0 + 100.0 * u + 10.0 * v, 20.0 + 50.0 * v];
        let mut points = Vec::new();
        for r in 0..=1 {
            for c in 0..=2 {
                let p = f(c as f32 / 2.0, r as f32);
                points.extend_from_slice(&p);
            }
        }
        for bilinear in [true, false] {
            let lattice = Lattice { points: &points, columns: 2, rows: 1, bilinear };
            for (u, v) in [(0.3, 0.7), (-1.0, 0.5), (2.5, -1.5), (5.0, 4.0)] {
                let (got, want) = (lattice.map(u, v), f(u, v));
                assert!((got[0] - want[0]).abs() < 1e-3 && (got[1] - want[1]).abs() < 1e-3, "{:?} vs {:?}", got, want);
            }
        }
    }
}
