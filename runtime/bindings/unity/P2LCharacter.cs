// Plays a .p2lrt rig in Unity and shows it as a texture on this object's renderer, drawn each frame by the
// runtime's software renderer (p2l_render). Needs ../csharp/P2lNative.cs and P2l.cs and the runtime library in
// Assets/Plugins. MIT License.
using PSD2Live.Runtime;
using UnityEngine;

namespace PSD2Live.Runtime.Unity
{
    [RequireComponent(typeof(Renderer))]
    public sealed class P2LCharacter : MonoBehaviour
    {
        [Tooltip("The exported .p2lrt, renamed to .bytes so Unity imports it as a TextAsset.")]
        public TextAsset rig;

        [Tooltip("The texture's width in pixels; the height follows the canvas.")]
        public int width = 512;

        [Tooltip("The clip to play on start, by id; empty for none.")]
        public string clip = "";

        private P2lRig handle;
        private Texture2D texture;
        private byte[] pixels;
        private int height;
        private float[] canvasToTexture;

        /// <summary>The playing rig, for setting parameters, gaze or lip sync; null while disabled.</summary>
        public P2lRig Rig { get { return handle; } }

        private void OnEnable()
        {
            if (rig == null || !P2l.Compatible())
            {
                Debug.LogError("P2LCharacter: no rig, or the runtime library does not match the bindings", this);
                enabled = false;
                return;
            }
            handle = P2lRig.Load(rig.bytes);
            height = Mathf.Max(1, Mathf.RoundToInt(width * handle.CanvasHeight / handle.CanvasWidth));
            float scale = width / handle.CanvasWidth;
            // Unity's textures start at the bottom row, the runtime's canvas at the top.
            canvasToTexture = new[] { scale, 0f, 0f, -scale, 0f, height };
            texture = new Texture2D(width, height, TextureFormat.RGBA32, false);
            pixels = new byte[width * height * 4];
            GetComponent<Renderer>().material.mainTexture = texture;
            for (int i = 0; i < handle.ClipCount; i++)
            {
                if (handle.ClipId(i) == clip) handle.Play(i);
            }
        }

        private void Update()
        {
            handle.Update(Time.deltaTime);
            handle.Render(pixels, width, height, canvasToTexture);
            texture.LoadRawTextureData(pixels);
            texture.Apply(false);
        }

        private void OnDisable()
        {
            if (handle != null) handle.Dispose();
            handle = null;
            if (texture != null) Destroy(texture);
            texture = null;
        }
    }
}
