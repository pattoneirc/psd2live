//! Compares the runtime with reference poses written by the editor's RuntimeConformanceTool:
//! `p2lrt-conformance <dir> [case-filter]`, one directory per case holding rig.p2lrt, poses.bin and
//! expected.bin. Prints each case's worst vertex, opacity and draw-order errors.

use p2l_runtime::{Evaluator, Rig};
use std::{env, fs, path::Path};

fn f32s(bytes: &[u8]) -> impl Iterator<Item = f32> + '_ {
    bytes.chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap()))
}

struct Report {
    vertex: f32,
    vertex_at: String,
    opacity: f32,
    draw_order: f32,
}

/// A physics trace: per step the frame time, the values before and the values after.
fn compare_physics(dir: &Path) -> Result<Report, String> {
    let rig = Rig::read(&fs::read(dir.join("rig.p2lrt")).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let trace = fs::read(dir.join("trace.bin")).map_err(|e| e.to_string())?;
    let steps = u32::from_le_bytes(trace[0..4].try_into().unwrap()) as usize;
    let n = u32::from_le_bytes(trace[4..8].try_into().unwrap()) as usize;
    let data: Vec<f32> = f32s(&trace[8..]).collect();
    let mut physics = p2l_runtime::Physics::new(&rig);
    let mut report = Report { vertex: 0.0, vertex_at: String::new(), opacity: 0.0, draw_order: 0.0 };
    for step in 0..steps {
        let row = &data[step * (1 + 2 * n)..(step + 1) * (1 + 2 * n)];
        let mut values = row[1..1 + n].to_vec();
        physics.step(&rig, row[0], &mut values);
        for (i, (g, w)) in values.iter().zip(&row[1 + n..]).enumerate() {
            let e = (g - w).abs();
            if e > report.vertex || e.is_nan() {
                report.vertex = if e.is_nan() { f32::INFINITY } else { e };
                report.vertex_at = format!("step {} parameter {} got {} want {}", step, rig.parameters[i].id, g, w);
            }
        }
    }
    Ok(report)
}

fn compare(dir: &Path) -> Result<Report, String> {
    if dir.join("trace.bin").exists() {
        return compare_physics(dir);
    }
    let rig = Rig::read(&fs::read(dir.join("rig.p2lrt")).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let poses = fs::read(dir.join("poses.bin")).map_err(|e| e.to_string())?;
    let expected = fs::read(dir.join("expected.bin")).map_err(|e| e.to_string())?;
    let count = u32::from_le_bytes(poses[0..4].try_into().unwrap()) as usize;
    let params = u32::from_le_bytes(poses[4..8].try_into().unwrap()) as usize;
    let values: Vec<f32> = f32s(&poses[8..]).collect();
    if env::var("P2L_DEBUG").is_ok() {
        for d in &rig.deformers {
            let kind = match &d.kind { p2l_runtime::rig::DeformerKind::Warp { .. } => "warp".to_string(), p2l_runtime::rig::DeformerKind::Rotation { base_angle, flip_x, flip_y, .. } => format!("rot base {} flip {} {}", base_angle, flip_x, flip_y) };
            let axes = match &d.kind { p2l_runtime::rig::DeformerKind::Warp { lattice, .. } => lattice.as_ref().map(|g| format!("{:?}", g.axes)), p2l_runtime::rig::DeformerKind::Rotation { pivot, .. } => pivot.as_ref().map(|g| format!("{:?} {:?}", g.axes, g.cells)) };
            eprintln!("deformer {} parent {:?} {} channels {} axes {:?}", d.id, d.parent, kind, d.channels.len(), axes);
            match &d.kind {
                p2l_runtime::rig::DeformerKind::Warp { shapes, .. } => for b in shapes { eprintln!("   shape p{} keys {:?} neutral {} present {:?} limits {:?} opac {:?}", b.parameter, b.keys, b.neutral, b.shapes.iter().map(|s| s.is_some()).collect::<Vec<_>>(), b.limits, b.shapes.iter().map(|s| s.as_ref().map(|s| s.opacity)).collect::<Vec<_>>()); },
                p2l_runtime::rig::DeformerKind::Rotation { shapes, .. } => for b in shapes { eprintln!("   shape p{} keys {:?} neutral {} limits {:?} shapes {:?}", b.parameter, b.keys, b.neutral, b.limits, b.shapes.iter().map(|s| s.as_ref().map(|s| (s.pivot, s.opacity))).collect::<Vec<_>>()); },
            }
        }
        for g in &rig.glues { eprintln!("glue {} {} {} intensity {} pairs {:?} channels {}", g.id, rig.meshes[g.mesh_a].id, rig.meshes[g.mesh_b].id, g.intensity, g.pairs, g.channels.len()); }
        for m in &rig.meshes { eprintln!("mesh {} parent {:?} axes {:?} opacity {}", m.id, m.parent, m.offsets.as_ref().map(|g| &g.axes), m.opacity);
            for b in &m.shapes { eprintln!("   shape p{} keys {:?} neutral {} limits {:?} shapes {:?}", b.parameter, b.keys, b.neutral, b.limits, b.shapes.iter().map(|s| s.as_ref().map(|s| (s.opacity, s.draw_order))).collect::<Vec<_>>()); } }
    }
    let mut evaluator = Evaluator::new();
    let mut report = Report { vertex: 0.0, vertex_at: String::new(), opacity: 0.0, draw_order: 0.0 };
    let mut at = 0;
    let read_f32 = |at: &mut usize| { let v = f32::from_le_bytes(expected[*at..*at + 4].try_into().unwrap()); *at += 4; v };
    for pose_index in 0..count {
        let pose = evaluator.evaluate(&rig, &values[pose_index * params..(pose_index + 1) * params]);
        for (m, mesh) in rig.meshes.iter().enumerate() {
            let opacity = read_f32(&mut at);
            let draw_order = read_f32(&mut at);
            let n = i32::from_le_bytes(expected[at..at + 4].try_into().unwrap());
            at += 4;
            if n < 0 {
                continue;
            }
            let want: Vec<f32> = f32s(&expected[at..at + n as usize * 4]).collect();
            at += n as usize * 4;
            if !opacity.is_nan() { report.opacity = report.opacity.max((pose.opacity[m] - opacity).abs()); }
            if !draw_order.is_nan() { report.draw_order = report.draw_order.max((pose.draw_order[m] - draw_order).abs()); }
            let got = &pose.vertices[m];
            if got.len() != want.len() {
                return Err(format!("mesh {} has {} values, expected {}", mesh.id, got.len(), want.len()));
            }
            if env::var("P2L_FRAMES").ok().map_or(false, |v| v == pose_index.to_string()) && m == 0 {
                for (i, t) in pose.deformers.iter().enumerate() { if let p2l_runtime::Transform::Rotation { .. } = t { eprintln!("  frame {} {:?}", rig.deformers[i].id, t); } }
            }
            if env::var("P2L_DEBUG").is_ok() {
                let e = got.iter().zip(&want).map(|(g, w)| (g - w).abs()).fold(0f32, f32::max);
                eprintln!("pose {} mesh {} err {:.4} opacity {} vs {}", pose_index, mesh.id, e, pose.opacity[m], opacity);
                if e > 0.01 && env::var("P2L_DEBUG").as_deref() == Ok("2") { eprintln!("  got {:?}
  want {:?}", got, want); }
            }
            for (i, (g, w)) in got.iter().zip(&want).enumerate() {
                let e = (g - w).abs();
                if e > report.vertex {
                    report.vertex = e;
                    report.vertex_at = format!("pose {} mesh {} value {} got {} want {}", pose_index, mesh.id, i, g, w);
                }
            }
        }
    }
    Ok(report)
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let root = Path::new(args.get(1).map(String::as_str).unwrap_or("../build/tools/runtime-conformance"));
    let filter = args.get(2).cloned().unwrap_or_default();
    let mut dirs: Vec<_> = fs::read_dir(root).expect("reference directory").filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.is_dir()).collect();
    dirs.sort();
    let mut failed = 0;
    for dir in dirs {
        let name = dir.file_name().unwrap().to_string_lossy().to_string();
        if !name.contains(&filter) { continue; }
        match compare(&dir) {
            Ok(r) => {
                let ok = r.vertex < 0.02 && r.opacity < 1e-4 && r.draw_order < 1e-3;
                if !ok { failed += 1; }
                println!("{} {:<22} vertex {:>10.5} opacity {:>8.5} order {:>8.3}  {}", if ok { "ok  " } else { "FAIL" }, name, r.vertex, r.opacity, r.draw_order, if ok { "" } else { &r.vertex_at });
            }
            Err(e) => { failed += 1; println!("ERR  {:<22} {}", name, e); }
        }
    }
    println!("{} failing", failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
