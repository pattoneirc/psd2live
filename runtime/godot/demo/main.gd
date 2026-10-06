extends Node2D
## Loads the rig named by `--rig <path>` after `--` on the command line (or the character's Rig Path),
## fits it to the window and, with `--shot <png>`, saves a frame after a second and quits.

var shot := ""

func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	var character: P2LCharacter = $Character
	for i in args.size() - 1:
		if args[i] == "--rig":
			character.load(args[i + 1])
		elif args[i] == "--shot":
			shot = args[i + 1]
	var ids := character.get_clip_ids()
	if ids.size() > 0:
		character.play(ids[0])
	# Fit the canvas into the window.
	var size := get_viewport_rect().size
	var canvas := character.get_canvas_size()
	character.position = size / 2
	character.scale = Vector2.ONE * minf(size.x / canvas.x, size.y / canvas.y)
	if shot != "":
		await get_tree().create_timer(1.0).timeout
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png(shot)
		get_tree().quit()
