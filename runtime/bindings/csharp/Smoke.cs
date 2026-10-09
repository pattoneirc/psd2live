// Checks the bindings against a built runtime: every declaration resolves to an entry point, and a rig loads,
// plays and renders. MIT License.
//   Smoke.exe model.p2lrt
using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

namespace PSD2Live.Runtime
{
    public static class Smoke
    {
        public static int Main(string[] args)
        {
            int resolved = 0;
            foreach (MethodInfo method in typeof(P2lNative).GetMethods(BindingFlags.Public | BindingFlags.Static))
            {
                if (method.GetCustomAttributes(typeof(DllImportAttribute), false).Length == 0) continue;
                Marshal.Prelink(method);
                resolved++;
            }
            Console.WriteLine("{0} entry points resolved", resolved);
            if (!P2l.Compatible()) { Console.Error.WriteLine("incompatible runtime"); return 1; }
            using (P2lRig rig = P2lRig.Load(File.ReadAllBytes(args[0])))
            {
                Console.WriteLine("{0} parameters, {1} meshes, {2} clips, canvas {3}x{4}", rig.ParameterCount, rig.MeshCount, rig.ClipCount,
                    rig.CanvasWidth, rig.CanvasHeight);
                if (rig.ClipCount > 0) rig.Play(0);
                for (int i = 0; i < 30; i++) rig.Update(1f / 30f);
                int width = 200, height = (int)(200 * rig.CanvasHeight / rig.CanvasWidth);
                var rgba = new byte[width * height * 4];
                if (!rig.Render(rgba, width, height)) { Console.Error.WriteLine("render failed"); return 1; }
                int drawn = 0;
                for (int i = 0; i < width * height; i++) if (rgba[i * 4 + 3] != 0) drawn++;
                Console.WriteLine("rendered {0}x{1}, {2} pixels drawn; failure: {3}", width, height, drawn, rig.Failure ?? "none");
                float[] vertices = rig.Vertices(0);
                Console.WriteLine("mesh 0 has {0} vertices, first at ({1}, {2})", vertices.Length / 2, vertices.Length > 0 ? vertices[0] : 0, vertices.Length > 1 ? vertices[1] : 0);
                return drawn > 0 && rig.Failure == null ? 0 : 1;
            }
        }
    }
}
