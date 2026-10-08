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

/// The same rig written as version 1 and as compressed version 2 must read as rig.p2lrt does and pose
/// bit for bit the same.
fn compare_variants(dir: &Path) -> Result<(), String> {
    let read = |name: &str| Rig::read_with(&fs::read(dir.join(name)).map_err(|e| e.to_string())?, true).map_err(|e| format!("{}: {}", name, e));
    let mut main = read("rig.p2lrt")?;
    main.meta.clear();
    let poses = fs::read(dir.join("poses.bin")).ok();
    for name in ["rig.v1.p2lrt", "rig.packed.p2lrt"] {
        if !dir.join(name).exists() {
            continue;
        }
        let mut other = read(name)?;
        other.meta.clear();
        // Version 1 has no parameter panel and no advanced data.
        if name == "rig.v1.p2lrt" {
            other.gui = main.gui.clone();
            other.extensions = main.extensions.clone();
        }
        if other != main {
            return Err(format!("{} reads differently from rig.p2lrt", name));
        }
        let Some(poses) = &poses else { continue };
        let params = u32::from_le_bytes(poses[4..8].try_into().unwrap()) as usize;
        let values: Vec<f32> = f32s(&poses[8..]).collect();
        let (mut a, mut b) = (Evaluator::new(), Evaluator::new());
        for pose in values.chunks(params.max(1)) {
            let (x, y) = (a.evaluate(&main, pose), b.evaluate(&other, pose));
            let bits = |v: &[f32]| v.iter().map(|f| f.to_bits()).collect::<Vec<_>>();
            if x.vertices.iter().zip(&y.vertices).any(|(p, q)| bits(p) != bits(q)) || bits(&x.opacity) != bits(&y.opacity) || bits(&x.draw_order) != bits(&y.draw_order) {
                return Err(format!("{} poses differently from rig.p2lrt", name));
            }
        }
    }
    Ok(())
}

fn compare(dir: &Path) -> Result<Report, String> {
    compare_variants(dir)?;
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

/// Advanced mode on a case: every feature its file offers on, over the case's poses and at the skins' keys.
/// Off-key it may differ from the bake by the bake's own chord error; at a key, where the bake is exact, it
/// must not. Returns (features, largest difference anywhere, largest at keys).
fn advanced(dir: &Path) -> Result<(u32, f32, f32), String> {
    use p2l_runtime::advanced::Advanced;
    let rig = Rig::read(&fs::read(dir.join("rig.p2lrt")).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let mut adv = Advanced::new();
    let features = adv.set(&rig, u32::MAX);
    let poses = fs::read(dir.join("poses.bin")).map_err(|e| e.to_string())?;
    let params = u32::from_le_bytes(poses[4..8].try_into().unwrap()) as usize;
    let values: Vec<f32> = f32s(&poses[8..]).collect();
    let (mut core, mut live) = (Evaluator::new(), Evaluator::new());
    let mut compare = |pose: &[f32]| -> Result<f32, String> {
        let a = core.evaluate(&rig, pose).vertices.clone();
        let b = &live.evaluate_ext(&rig, pose, Some(&mut adv)).vertices;
        let mut worst = 0f32;
        for (x, y) in a.iter().flatten().zip(b.iter().flatten()) {
            if !y.is_finite() {
                return Err("advanced mode produced a non-finite vertex".into());
            }
            worst = worst.max((x - y).abs());
        }
        Ok(worst)
    };
    let mut anywhere = 0f32;
    for pose in values.chunks(params.max(1)) {
        anywhere = anywhere.max(compare(pose)?);
    }
    // Each skin's axes all at keys - every combination up to 256, else each axis in turn with the others at
    // their first keys - and the other parameters at their defaults.
    let mut at_keys = 0f32;
    for skin in &rig.extensions.skins {
        let combinations: usize = skin.axes.iter().map(|(_, k)| k.len()).product();
        let poses: Vec<Vec<usize>> = if combinations <= 256 {
            (0..combinations).map(|mut i| skin.axes.iter().map(|(_, k)| { let c = i % k.len(); i /= k.len(); c }).collect()).collect()
        } else {
            skin.axes.iter().enumerate().flat_map(|(a, (_, k))| (0..k.len()).map(move |c| (0..skin.axes.len()).map(|b| if b == a { c } else { 0 }).collect::<Vec<_>>()).collect::<Vec<_>>()).collect()
        };
        for choice in poses {
            let mut pose = rig.defaults();
            for ((parameter, keys), c) in skin.axes.iter().zip(&choice) {
                pose[*parameter] = keys[*c];
            }
            at_keys = at_keys.max(compare(&pose)?);
        }
    }
    Ok((features, anywhere, at_keys))
}

/// Against the same rig baked finely (rig.fine.p2lrt), over the case's poses: the worst vertex error of the
/// default bake and of advanced mode on it, per mesh both versions skin alike.
fn against_fine(dir: &Path) -> Result<Option<(f32, f32)>, String> {
    use p2l_runtime::advanced::Advanced;
    let Ok(fine) = fs::read(dir.join("rig.fine.p2lrt")) else { return Ok(None) };
    let fine = Rig::read(&fine).map_err(|e| e.to_string())?;
    let rig = Rig::read(&fs::read(dir.join("rig.p2lrt")).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let mut adv = Advanced::new();
    adv.set(&rig, u32::MAX);
    // Each skin axis alone halfway between its keys, where a chord strays furthest from its arc; the rest at defaults.
    let mut sweeps: Vec<Vec<f32>> = Vec::new();
    for skin in &rig.extensions.skins {
        for (parameter, keys) in &skin.axes {
            for pair in keys.windows(2) {
                let mut pose = rig.defaults();
                pose[*parameter] = (pair[0] + pair[1]) / 2.0;
                sweeps.push(pose);
            }
        }
    }
    let (mut reference, mut core, mut live) = (Evaluator::new(), Evaluator::new(), Evaluator::new());
    let (mut baked, mut advanced) = (0f32, 0f32);
    if env::var("P2L_SKINS").is_ok() {
        for skin in &rig.extensions.skins {
            let mut worst = (0f32, String::new());
            for (parameter, keys) in &skin.axes {
                for pair in keys.windows(2) {
                    let mut pose = rig.defaults();
                    pose[*parameter] = (pair[0] + pair[1]) / 2.0;
                    let a = core.evaluate(&rig, &pose).vertices[skin.mesh].clone();
                    let b = live.evaluate_ext(&rig, &pose, Some(&mut adv)).vertices[skin.mesh].clone();
                    let e = a.iter().zip(&b).map(|(x, y)| (x - y).abs()).fold(0f32, f32::max);
                    if e > worst.0 {
                        worst = (e, format!("{} at {} (keys {} {})", rig.parameters[*parameter].id, pose[*parameter], pair[0], pair[1]));
                    }
                }
            }
            let bones: Vec<String> = skin.bones.iter().map(|b| rig.deformer_or_bone(*b).id.clone()).collect();
            eprintln!("skin {} bones {:?} worst {:.3} {}", rig.meshes[skin.mesh].id, bones, worst.0, worst.1);
            // Against the fine bake at the same midpoints.
            let Some(f) = fine.mesh(&rig.meshes[skin.mesh].id) else { continue };
            let fine_axes = fine.meshes[f].offsets.as_ref().map(|g| g.axes.iter().map(|a| (fine.parameters[a.parameter].id.clone(), a.keys.len())).collect::<Vec<_>>());
            let (mut eb, mut ea) = (0f32, 0f32);
            for (parameter, keys) in &skin.axes {
                for pair in keys.windows(2) {
                    let mut pose = rig.defaults();
                    pose[*parameter] = (pair[0] + pair[1]) / 2.0;
                    let by_id: Vec<f32> = fine.parameters.iter().map(|q| rig.parameter(&q.id).map_or(q.default, |i| pose[i])).collect();
                    let want = reference.evaluate(&fine, &by_id).vertices[f].clone();
                    let a = core.evaluate(&rig, &pose).vertices[skin.mesh].clone();
                    let b = live.evaluate_ext(&rig, &pose, Some(&mut adv)).vertices[skin.mesh].clone();
                    eb = eb.max(want.iter().zip(&a).map(|(x, y)| (x - y).abs()).fold(0f32, f32::max));
                    ea = ea.max(want.iter().zip(&b).map(|(x, y)| (x - y).abs()).fold(0f32, f32::max));
                }
            }
            eprintln!("     vs fine (axes {:?}): baked {:.3} advanced {:.3}", fine_axes, eb, ea);
        }
    }
    if env::var("P2L_DEBUG").is_ok() {
        let rest = rig.defaults();
        let by_id: Vec<f32> = fine.parameters.iter().map(|q| rig.parameter(&q.id).map_or(q.default, |i| rest[i])).collect();
        let want = reference.evaluate(&fine, &by_id).vertices.clone();
        let got = core.evaluate(&rig, &rest).vertices.clone();
        for (m, mesh) in rig.meshes.iter().enumerate() {
            if let Some(f) = fine.mesh(&mesh.id) {
                let e = want[f].iter().zip(&got[m]).map(|(a, b)| (a - b).abs()).fold(0f32, f32::max);
                eprintln!("rest {} vertices {} vs {} error {}", mesh.id, got[m].len() / 2, want[f].len() / 2, e);
            }
        }
    }
    for pose in &sweeps {
        let by_id = |r: &Rig, p: &[f32]| -> Vec<f32> { fine.parameters.iter().map(|q| r.parameter(&q.id).map_or(q.default, |i| p[i])).collect() };
        let want = reference.evaluate(&fine, &by_id(&rig, pose)).vertices.clone();
        let a = core.evaluate(&rig, pose).vertices.clone();
        let b = live.evaluate_ext(&rig, pose, Some(&mut adv)).vertices.clone();
        for (m, mesh) in rig.meshes.iter().enumerate() {
            if rig.extensions.skins.iter().all(|s| s.mesh != m) {
                continue;
            }
            let Some(f) = fine.mesh(&mesh.id) else { continue };
            if want[f].len() != a[m].len() {
                continue;
            }
            for ((w, x), y) in want[f].iter().zip(&a[m]).zip(&b[m]) {
                baked = baked.max((w - x).abs());
                advanced = advanced.max((w - y).abs());
            }
        }
    }
    Ok(Some((baked, advanced)))
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.get(1).map(String::as_str) == Some("--advanced") {
        let root = Path::new(args.get(2).map(String::as_str).unwrap_or("../build/tools/runtime-conformance"));
        let mut dirs: Vec<_> = fs::read_dir(root).expect("reference directory").filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.join("poses.bin").exists()).collect();
        dirs.sort();
        let mut failed = 0;
        for dir in dirs {
            let name = dir.file_name().unwrap().to_string_lossy().to_string();
            match advanced(&dir) {
                Ok((0, ..)) => {}
                Ok((features, anywhere, at_keys)) => {
                    // At the keys the bake is exact and so must advanced mode be. Between them it may differ by the
                    // chord's departure from the arc; how far a skeleton sample's finer bake lies from both is
                    // reported (with P2L_SKINS, per skin) but not judged: the bake's own solve shifts with its keys.
                    let fine = against_fine(&dir);
                    let ok = at_keys < 0.01;
                    if !ok { failed += 1; }
                    let reference = match fine {
                        Ok(Some((baked, advanced))) => format!("  vs fine bake: baked {:.4} advanced {:.4}", baked, advanced),
                        Ok(None) => String::new(),
                        Err(e) => format!("  fine bake: {}", e),
                    };
                    println!("{} {:<22} features {:>2} difference {:>9.4} at keys {:>9.5}{}", if ok { "ok  " } else { "FAIL" }, name, features, anywhere, at_keys, reference);
                }
                Err(e) => { failed += 1; println!("ERR  {:<22} {}", name, e); }
            }
        }
        println!("{} failing", failed);
        std::process::exit(if failed == 0 { 0 } else { 1 });
    }
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
