use std::fs::File;
use std::io::{Seek, SeekFrom};
use std::os::fd::FromRawFd;
use std::sync::{Arc, Mutex, Once, OnceLock, atomic::{AtomicBool, Ordering}};
use std::panic::{catch_unwind, AssertUnwindSafe};

use gyroflow_core::{StabilizationManager, gyro_source::FileLoadOptions,
                    gpu::{Buffers, BufferDescription, BufferSource}, stabilization::RGBA8};
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JObject};
use jni::sys::{jint, jlong, jstring};
use jni::EnvUnowned;

// The two probe buttons run on different Java worker threads. Store the
// *same* Gyroflow manager for both steps, instead of relying on thread_local!
// or mixing two different .so builds.
static PREPARED: OnceLock<Mutex<Option<StabilizationManager>>> = OnceLock::new();
static ANDROID_INIT: Once = Once::new();
static ANDROID_READY: AtomicBool = AtomicBool::new(false);

fn prepared() -> &'static Mutex<Option<StabilizationManager>> {
    PREPARED.get_or_init(|| Mutex::new(None))
}

fn safe<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => Err("Gyroflow内部で例外が発生しました（強制終了を回避）".into()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_sakaguchi_dancerecenter_GyroflowBridge_nativeInit(
    mut env: EnvUnowned, _class: JClass, context: JObject) {
    env.with_env(|env| {
        let vm = env.get_java_vm()?;
        let global_context = env.new_global_ref(context)?;
        ANDROID_INIT.call_once(|| {
            // ndk-context must retain a global JNI reference for the lifetime
            // of the process; releasing it invalidates Gyroflow's filesystem.
            unsafe { ndk_context::initialize_android_context(
                vm.get_raw().cast(), global_context.as_obj().as_raw().cast()
            ); }
            std::mem::forget(global_context);
            ANDROID_READY.store(true, Ordering::Release);
        });
        Ok::<(), jni::errors::Error>(())
    }).resolve::<ThrowRuntimeExAndDefault>();
}

fn reply(mut env: EnvUnowned, value: String) -> jstring {
    env.with_env(|env| env.new_string(value).map(|v| v.into_raw()))
        .resolve::<ThrowRuntimeExAndDefault>()
}

unsafe fn file_from_fd(fd: jint) -> Result<File, String> {
    let copied = unsafe { libc::dup(fd) };
    if copied < 0 { return Err("動画ファイルを複製できません".to_string()); }
    let mut file = unsafe { File::from_raw_fd(copied) };
    file.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
    Ok(file)
}

fn manager_from_fd(fd: jint, duration_ms: jlong, width: jint, height: jint, fps_x1000: jint) -> Result<(StabilizationManager, usize), String> {
    if !ANDROID_READY.load(Ordering::Acquire) {
        return Err("Android初期化が未完了です。先にnativeInitを呼んでください".into());
    }
    let mut file = unsafe { file_from_fd(fd)? };
    let size = file.metadata().map_err(|e| e.to_string())?.len() as usize;
    let manager = StabilizationManager::default();
    let fps = (fps_x1000 as f64 / 1000.0).max(1.0);
    let duration = (duration_ms as f64).max(1.0);
    manager.init_from_video_data(duration, fps, (duration / 1000.0 * fps).round() as usize,
                                 (width.max(1) as usize, height.max(1) as usize));
    manager.load_gyro_data(&mut file, size, "zv1.mp4", true, &FileLoadOptions::default(), |_| {},
                           Arc::new(AtomicBool::new(false)))
        .map_err(|e| e.to_string())?;
    Ok((manager, size))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_sakaguchi_dancerecenter_GyroflowBridge_nativeCoreVersion(
    env: EnvUnowned, _class: JClass) -> jstring {
    reply(env, "Gyroflow Core 1.6.3: horizon export".to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_sakaguchi_dancerecenter_GyroflowBridge_nativeInspectZV1(
    env: EnvUnowned, _class: JClass, fd: jint, duration_ms: jlong, width: jint, height: jint, fps_x1000: jint) -> jstring {
    let value = match safe(|| manager_from_fd(fd, duration_ms, width, height, fps_x1000)) {
        Ok((manager, _)) => {
            let gyro = manager.gyro.read();
            let samples = gyro.quaternions.len();
            let source = gyro.file_metadata.read().detected_source.clone().unwrap_or_else(|| "Sony ZV-1".to_string());
            format!("OK|{}|samples={}|{}x{}", source, samples, width, height)
        }
        Err(e) => format!("ERR|Gyroflow解析: {}", e),
    };
    reply(env, value)
}

fn load_calibration(manager: &StabilizationManager) -> Result<(), String> {
    let lens = manager.lens.read();
    let embedded = lens.fisheye_params.camera_matrix.len() == 3
        && !lens.fisheye_params.distortion_coeffs.is_empty()
        && lens.calib_dimension.w > 0 && lens.calib_dimension.h > 0;
    drop(lens);
    if embedded {
        return Ok(()); // Some Sony files embed their own lens metadata.
    }
    let id = manager.camera_id.read().as_ref()
        .map(|v| v.get_identifier_for_autoload()).unwrap_or_default();
    if id.is_empty() { return Err("ZV-1のレンズ識別情報がありません".into()); }
    {
        let mut db = manager.lens_profile_db.write();
        if !db.loaded { db.load_all(); }
        if !db.contains_id(&id) {
            return Err(format!("レンズプロファイルが見つかりません: {id}"));
        }
    }
    manager.load_lens_profile(&id).map_err(|e| format!("レンズ読込: {e}"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_sakaguchi_dancerecenter_GyroflowBridge_nativePrepareStabilization(
    env: EnvUnowned, _class: JClass, fd: jint, duration_ms: jlong,
    width: jint, height: jint, fps_x1000: jint) -> jstring {
    let result = safe(|| {
        let (manager, _) = manager_from_fd(fd, duration_ms, width, height, fps_x1000)?;
        let samples = manager.gyro.read().quaternions.len();
        if samples == 0 { return Err("ジャイロサンプルがありません".into()); }
        load_calibration(&manager)?;
        manager.set_stab_enabled(true);
        manager.set_output_size(width as usize, height as usize);
        manager.recompute_blocking();
        *prepared().lock().map_err(|_| "補正状態のロックに失敗".to_string())? = Some(manager);
        Ok(format!("samples={samples};lens=OK;path=GyroflowCore"))
    });
    reply(env, match result { Ok(s) => format!("OK|{s}"), Err(e) => format!("ERR|{e}") })
}

// The existing Java probe passes an RGBA preview as two byte arrays. Its
// width is not an argument, so recover it from the byte length and the source
// aspect ratio; reject ambiguous or distorted dimensions.
fn preview_dimensions(bytes: usize, source_w: usize, source_h: usize) -> Result<(usize, usize), String> {
    if bytes == 0 || bytes % 4 != 0 || source_w == 0 || source_h == 0 {
        return Err("RGBA画像のサイズが不正です".into());
    }
    let pixels = bytes / 4;
    let center = ((pixels as f64 * source_w as f64 / source_h as f64).sqrt()).round() as isize;
    let mut best: Option<(usize, usize, f64)> = None;
    for candidate in (center - 64).max(1)..=(center + 64) {
        let w = candidate as usize;
        if pixels % w != 0 { continue; }
        let h = pixels / w;
        let error = ((w as f64 / h as f64) / (source_w as f64 / source_h as f64) - 1.0).abs();
        if best.as_ref().is_none_or(|(_, _, prev)| error < *prev) {
            best = Some((w, h, error));
        }
    }
    match best {
        Some((w, h, error)) if error < 0.01 => Ok((w, h)),
        _ => Err("プレビューの縦横比を特定できません".into()),
    }
}

fn transform_preview(input: &mut [u8], output: &mut [u8],
                     source_w: jint, source_h: jint, fps_x1000: jint,
                     timestamp_us: jlong) -> Result<String, String> {
    if input.len() != output.len() { return Err("入力と出力のRGBAサイズが違います".into()); }
    let (w, h) = preview_dimensions(input.len(), source_w.max(0) as usize, source_h.max(0) as usize)?;
    let mut slot = prepared().lock().map_err(|_| "補正状態のロックに失敗".to_string())?;
    let manager = slot.as_mut().ok_or("先に①ジャイロ解析＋補正計算を実行してください")?;
    // Gyroflow calibrations retain the camera geometry. Rendering the same
    // aspect ratio at preview resolution is the only concession in this test.
    manager.set_size(w, h);
    manager.set_output_size(w, h);
    manager.recompute_blocking();
    // Gyroflow's Android-capable controller passes the frame index along with
    // the timestamp. Keep the same mapping for this one-frame diagnostic.
    let frame_index = ((timestamp_us.max(0) as f64 / 1_000_000.0)
        * (fps_x1000.max(1) as f64 / 1000.0)).round() as usize;
    let info = manager.process_pixels::<RGBA8>(timestamp_us, Some(frame_index), &mut Buffers {
        input: BufferDescription {
            size: (w, h, w * 4), data: BufferSource::Cpu { buffer: input },
            ..Default::default()
        },
        output: BufferDescription {
            size: (w, h, w * 4), data: BufferSource::Cpu { buffer: output },
            ..Default::default()
        },
    }).map_err(|e| format!("Gyroflow CPU画素変形: {e}"))?;
    let changed_pixels = input.chunks_exact(4).zip(output.chunks_exact(4))
        .filter(|(before, after)| before != after).count();
    if changed_pixels == 0 {
        return Err("補正後の画素に変化がありません。時刻とジャイロ補正量を確認してください".into());
    }
    Ok(format!("{}x{};backend={};frame={frame_index};time_us={timestamp_us};changed_pixels={changed_pixels}", w, h, info.backend))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_sakaguchi_dancerecenter_GyroflowBridge_nativeStabilizeFrame(
    mut env: EnvUnowned, _class: JClass, _fd: jint, _duration_ms: jlong,
    source_w: jint, source_h: jint, fps_x1000: jint, timestamp_us: jlong,
    input: JByteArray, output: JByteArray) -> jstring {
    env.with_env(|env| {
        let status = (|| -> Result<String, String> {
            let mut pixels = env.convert_byte_array(&input).map_err(|e| e.to_string())?;
            let mut out = vec![0u8; pixels.len()];
            let info = safe(|| transform_preview(&mut pixels, &mut out,
                                                  source_w, source_h, fps_x1000, timestamp_us))?;
            let signed: Vec<i8> = out.into_iter().map(|byte| byte as i8).collect();
            env.set_byte_array_region(&output, 0, &signed).map_err(|e| e.to_string())?;
            Ok(info)
        })();
        let text = match status { Ok(v) => format!("OK|{v}"), Err(e) => format!("ERR|{e}") };
        env.new_string(text).map(|v| v.into_raw())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

// Produces one horizon correction angle per 100 ms. The values are the Gyroflow
// correction quaternions after 100% horizon lock, in radians for FFmpeg rotate.
#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_sakaguchi_dancerecenter_GyroflowBridge_nativeHorizonCommands(
    env: EnvUnowned, _class: JClass, fd: jint, duration_ms: jlong, width: jint, height: jint, fps_x1000: jint) -> jstring {
    let value = match safe(|| manager_from_fd(fd, duration_ms, width, height, fps_x1000)) {
        Ok((manager, _)) => {
            manager.set_horizon_lock(100.0, 0.0, false, 0.0, false, 0.0, 0.0, 0.0, 0.0);
            manager.recompute_blocking();
            let gyro = manager.gyro.read();
            if gyro.smoothed_quaternions.is_empty() { "ERR|水平補正用の回転データが作れません".to_string() }
            else {
                let mut out = String::from("OK|");
                let mut t = 0.0f64;
                let end = (duration_ms as f64 / 1000.0).max(0.1);
                let mut max_angle = 0.0f64;
                while t <= end + 0.001 {
                    let q = gyro.smoothed_quat_at_timestamp(t * 1000.0);
                    let (_, _, roll) = q.euler_angles();
                    let angle = roll.clamp(-0.7, 0.7);
                    max_angle = max_angle.max(angle.abs());
                    out.push_str(&format!("{:.3},{:.6};", t, angle));
                    t += 0.1;
                }
                format!("{}|max={:.2}", out, max_angle.to_degrees())
            }
        }
        Err(e) => format!("ERR|Gyroflow水平解析: {}", e),
    };
    reply(env, value)
}
