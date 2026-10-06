extends SceneTree
## Headless check: `godot --headless --path demo --script smoke_test.gd -- <rig.p2lrt>` loads the rig,
## advances a second of clips, behaviors and physics, and exits non-zero on a failure.

func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	var character := P2LCharacter.new()
	root.add_child(character)
	if args.is_empty() or not character.load(args[0]):
		push_error("smoke test: the rig did not load")
		quit(1)
		return
	var parameters := character.get_parameter_ids()
	if parameters.is_empty():
		push_error("smoke test: no parameters")
		quit(1)
		return
	character.set_parameter(parameters[0], 1.0)
	var clips := character.get_clip_ids()
	if clips.size() > 0:
		character.play(clips[0])
	for i in 60:
		character.advance(1.0 / 60.0)
	print("smoke test: %d parameters, %d clips, %s = %f" % [parameters.size(), clips.size(), parameters[0], character.get_parameter(parameters[0])])
	quit(0)
