// Helpers over P2lNative and a rig handle that frees itself, for Unity and .NET hosts. MIT License.
using System;
using System.Runtime.InteropServices;
using System.Text;

namespace PSD2Live.Runtime
{
    public static class P2l
    {
        /// <summary>A UTF-8 string the runtime returned, or null for a null pointer.</summary>
        public static string Utf8(IntPtr text)
        {
            if (text == IntPtr.Zero) return null;
            int length = 0;
            while (Marshal.ReadByte(text, length) != 0) length++;
            var bytes = new byte[length];
            Marshal.Copy(text, bytes, 0, length);
            return Encoding.UTF8.GetString(bytes);
        }

        /// <summary>[text] as the null-terminated UTF-8 the runtime takes.</summary>
        public static byte[] Text(string text)
        {
            var bytes = Encoding.UTF8.GetBytes(text);
            Array.Resize(ref bytes, bytes.Length + 1);
            return bytes;
        }

        /// <summary>Whether the loaded library implements the ABI these declarations were generated from.</summary>
        public static bool Compatible()
        {
            uint abi;
            try { abi = P2lNative.p2l_abi_version(); }
            catch (EntryPointNotFoundException) { return false; }
            return abi >> 16 == P2lNative.ABI_VERSION_MAJOR && (abi & 0xffff) >= P2lNative.ABI_VERSION_MINOR;
        }
    }

    /// <summary>One loaded rig: its parameters, clips, physics and last pose. Not thread-safe; Dispose frees it.</summary>
    public sealed class P2lRig : IDisposable
    {
        public IntPtr Handle { get; private set; }

        private P2lRig(IntPtr handle) { Handle = handle; }

        /// <summary>Loads the bytes of a .p2lrt file; throws ArgumentException with the runtime's message.</summary>
        public static P2lRig Load(byte[] bytes)
        {
            var error = new byte[512];
            IntPtr handle = P2lNative.p2l_rig_load(bytes, (UIntPtr)bytes.Length, error, (UIntPtr)error.Length);
            if (handle == IntPtr.Zero)
            {
                int end = Array.IndexOf(error, (byte)0);
                throw new ArgumentException(Encoding.UTF8.GetString(error, 0, end < 0 ? error.Length : end));
            }
            return new P2lRig(handle);
        }

        /// <summary>Why a call panicked inside the runtime, after which the rig answers nothing; null while it works.</summary>
        public string Failure { get { return P2l.Utf8(P2lNative.p2l_rig_failure(Handle)); } }

        public float CanvasWidth { get { float w, h; P2lNative.p2l_canvas(Handle, out w, out h); return w; } }
        public float CanvasHeight { get { float w, h; P2lNative.p2l_canvas(Handle, out w, out h); return h; } }

        public int ParameterCount { get { return (int)P2lNative.p2l_parameter_count(Handle); } }
        public string ParameterId(int index) { return P2l.Utf8(P2lNative.p2l_parameter_id(Handle, (uint)index)); }
        public int ParameterIndex(string id) { return P2lNative.p2l_parameter_index(Handle, P2l.Text(id)); }
        public void SetParameter(int index, float value) { P2lNative.p2l_set_parameter(Handle, (uint)index, value); }

        public int ClipCount { get { return (int)P2lNative.p2l_clip_count(Handle); } }
        public string ClipId(int index) { return P2l.Utf8(P2lNative.p2l_clip_id(Handle, (uint)index)); }
        public void Play(int clip) { P2lNative.p2l_play(Handle, clip); }

        public void Update(float seconds) { P2lNative.p2l_update(Handle, seconds); }
        public void Evaluate() { P2lNative.p2l_evaluate(Handle); }
        public void LookAt(float x, float y) { P2lNative.p2l_look_at(Handle, x, y); }
        public void LipSync(float level) { P2lNative.p2l_lip_sync(Handle, level); }

        public int MeshCount { get { return (int)P2lNative.p2l_mesh_count(Handle); } }

        /// <summary>Mesh [index]'s vertices from the last evaluation, canvas pixels with y down, two floats each.</summary>
        public float[] Vertices(int index)
        {
            var values = new float[P2lNative.p2l_mesh_vertex_count(Handle, (uint)index) * 2];
            IntPtr vertices = P2lNative.p2l_mesh_vertices(Handle, (uint)index);
            if (vertices != IntPtr.Zero && values.Length > 0) Marshal.Copy(vertices, values, 0, values.Length);
            return values;
        }

        /// <summary>
        /// Draws the last evaluation into [rgba], width x height RGBA8 rows top first, with the runtime's software
        /// renderer; null [transform] fits the canvas into the image.
        /// </summary>
        public bool Render(byte[] rgba, int width, int height, float[] transform = null, bool straight = true)
        {
            return P2lNative.p2l_render(Handle, rgba, (uint)width, (uint)height, transform, straight ? P2lNative.RENDER_STRAIGHT : 0);
        }

        public void Dispose()
        {
            if (Handle == IntPtr.Zero) return;
            P2lNative.p2l_rig_free(Handle);
            Handle = IntPtr.Zero;
        }
    }
}
