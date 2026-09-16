"""Asset contracts required by the GeckoLib rendering adapters; no game/Gradle needed."""
import json
from pathlib import Path
import unittest

ASSETS = Path(__file__).resolve().parents[3] / "src/main/resources/assets/zillion"


def geometry(name):
    return json.loads((ASSETS / "geo" / (name + ".geo.json")).read_text(encoding="utf-8"))["minecraft:geometry"][0]


def bones(name):
    return {bone["name"]: bone for bone in geometry(name)["bones"]}


class RiderModelContracts(unittest.TestCase):
    def test_full_legs_are_in_boots(self):
        model = bones("gazerzero")
        for side in ("Right", "Left"):
            self.assertFalse(model["armor" + side + "Leg"].get("cubes"))
            boot = model["armor" + side + "Boot"]
            self.assertGreater(len(boot["cubes"]), 0)
            self.assertEqual(boot["parent"], "biped" + side + "Leg")
            self.assertEqual(boot["pivot"][1], 12)

    def test_driver_needs_body_slot_override_and_root_transform(self):
        model = bones("zillion_driver")
        self.assertEqual(model["armorBody"]["parent"], "bipedBody")
        self.assertNotIn("parent", model["bone"])
        self.assertNotEqual(model["bone"]["pivot"], model["armorBody"]["pivot"])
        self.assertNotIn("armorRightLeg", model)

    def test_docking_bones_and_pivots(self):
        model = bones("gazerzero")
        expected = [
            ("armorBody", [0, 20.00912, -1.82406], [10, 0, 0]),
            ("armorRightArm", [-6.9082, 21.46427, 0.6], [0, 0, 0]),
            ("armorLeftArm", [6.9082, 21.46427, 0.6], [0, 0, 0]),
            ("armorRightBoot", [-2, 6.00912, -1.82406], [22.5, 0, 0]),
            ("armorLeftBoot", [2, 6.00912, -1.82406], [22.5, 0, 0]),
        ]
        for i, (parent, pivot, rotation) in enumerate(expected, 1):
            bone = model["wingman" + str(i)]
            self.assertEqual(bone["parent"], parent)
            self.assertEqual(bone["pivot"], pivot)
            self.assertEqual(bone.get("rotation", [0, 0, 0]), rotation)
            self.assertTrue(bone["cubes"])

    def test_free_flight_uses_distinct_uv_layout(self):
        armor, drone = geometry("gazerzero"), geometry("wingman")
        self.assertEqual(armor["description"]["texture_width"], 128)
        self.assertEqual(drone["description"]["texture_width"], 32)
        self.assertEqual(bones("wingman")["wingman"]["pivot"], [0.9082, 0.46427, 0.6])
        self.assertEqual(bones("wingman")["wingman"]["rotation"], [0, 0, 25])



if __name__ == "__main__":
    unittest.main()
