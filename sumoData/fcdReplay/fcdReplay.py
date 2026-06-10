#!/usr/bin/env python
# Eclipse SUMO, Simulation of Urban MObility; see https://eclipse.dev/sumo
# Copyright (C) 2008-2025 German Aerospace Center (DLR) and others.
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# https://www.eclipse.org/legal/epl-2.0/
# This Source Code may also be made available under the following Secondary
# Licenses when the conditions for such availability set forth in the Eclipse
# Public License 2.0 are satisfied: GNU General Public License, version 2
# or later which is available at
# https://www.gnu.org/licenses/old-licenses/gpl-2.0-standalone.html
# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later

# @file    fcdReplay.py
# @author  Jakob Erdmann
# @date    2023-01-11

"""
Replay an fcd-file as moving oriented bounding boxes on top of a simulation
(or empty network). Each vehicle is represented as a box with a configurable
longitudinal length and lateral width, oriented according to the vehicle's
heading angle stored in the FCD data.
"""

from __future__ import print_function
import os
import sys
import math
from collections import defaultdict
sys.path.append(os.path.join(os.environ["SUMO_HOME"], 'tools'))
import sumolib  # noqa
import traci  # noqa

# Default box dimensions (metres)
DEFAULT_LENGTH = 5.0   # longitudinal (along heading)
DEFAULT_WIDTH = 2.0    # lateral (perpendicular to heading)


def make_box_shape(x, y, angle_deg, length, width):
    """Return the four corners of an oriented rectangle centred at (x, y).

    Parameters
    ----------
    x, y      : centre position in SUMO network coordinates
    angle_deg : heading in degrees (SUMO convention: 0 = North, clockwise)
    length    : longitudinal extent in metres
    width     : lateral extent in metres

    Returns
    -------
    list of (x, y) tuples forming a closed polygon (first == last)
    """
    # Convert SUMO heading (clockwise from North) to standard math angle
    # (counter-clockwise from East) so we can use cos/sin directly.
    theta = math.radians(90.0 - angle_deg)

    # Unit vectors along and across the vehicle
    fwd = (math.cos(theta), math.sin(theta))
    lat = (-math.sin(theta), math.cos(theta))

    half_l = length / 2.0
    half_w = width / 2.0

    corners = [
        (x + fwd[0] * half_l - lat[0] * half_w,
         y + fwd[1] * half_l - lat[1] * half_w),
        (x + fwd[0] * half_l + lat[0] * half_w,
         y + fwd[1] * half_l + lat[1] * half_w),
        (x - fwd[0] * half_l + lat[0] * half_w,
         y - fwd[1] * half_l + lat[1] * half_w),
        (x - fwd[0] * half_l - lat[0] * half_w,
         y - fwd[1] * half_l - lat[1] * half_w),
    ]
    # Close the polygon
    corners.append(corners[0])
    return corners


def main():
    parser = sumolib.options.ArgumentParser()
    parser.add_argument("-k", "--sumo-config", category="input", default="sumo.sumocfg",
                        help="sumo config file")
    parser.add_argument("-f", "--fcd-files", category="processing", dest="fcdFiles",
                        help="the fcd files to replay")
    parser.add_argument("--geo", category="processing", action="store_true", default=False,
                        help="use fcd data in lon,lat format")
    parser.add_argument("--length", category="processing", type=float, default=DEFAULT_LENGTH,
                        help="longitudinal box length in metres (default: %.1f)" % DEFAULT_LENGTH)
    parser.add_argument("--width", category="processing", type=float, default=DEFAULT_WIDTH,
                        help="lateral box width in metres (default: %.1f)" % DEFAULT_WIDTH)
    parser.add_argument("-v", "--verbose", category="processing", action="store_true", default=False,
                        help="tell me what you are doing")
    parser.add_argument("sumo_args", nargs="*", catch_all=True, help="additional sumo arguments")
    options = parser.parse_args()

    sumoBinary = sumolib.checkBinary("sumo-gui")
    traci.start([sumoBinary, "-c", options.sumo_config] + options.sumo_args)
    t = traci.simulation.getTime()
    deltaT = traci.simulation.getDeltaT()

    fcdData = defaultdict(list)   # time -> list of objects
    lastTime = {}                  # objectID -> last known time

    for fname in options.fcdFiles.split(','):
        if options.verbose:
            print("Loading fcd data from '%s'" % fname)
        for ts in sumolib.xml.parse(fname, 'timestep'):
            time = sumolib.miscutils.parseTime(ts.time)
            if time < t:
                continue
            for obj in ts.getChildList():
                obj.x = float(obj.x)
                obj.y = float(obj.y)
                if options.geo:
                    obj.x, obj.y = traci.simulation.convertGeo(obj.x, obj.y, True)
                # angle attribute is written by SUMO FCD output if included in
                # fcd-output.attributes; fall back to 90° (East/horizontal) if absent or None
                raw_angle = getattr(obj, 'angle', None)
                obj._angle = float(raw_angle) if raw_angle is not None else 90.0
                fcdData[time].append(obj)
                lastTime[obj.id] = time

    removeAtTime = defaultdict(list)   # time -> object IDs to remove
    for oID, rt in lastTime.items():
        removeAtTime[rt + deltaT].append(oID)

    end = max(max(lastTime.values()), traci.simulation.getEndTime())
    created = set()

    while t <= end:
        for obj in fcdData.get(t, []):
            shape = make_box_shape(obj.x, obj.y, obj._angle, options.length, options.width)

            if obj.id in created:
                traci.polygon.setShape(obj.id, shape)
            else:
                traci.polygon.add(
                    obj.id,
                    shape,
                    color=(255, 0, 0, 200),
                    fill=True,
                    polygonType="fcdReplay",
                    layer=340,
                )
                created.add(obj.id)

            # Forward any extra FCD attributes as polygon parameters
            for a, v in obj.getAttributes():
                if a in ('x', 'y', 'id', 'angle'):
                    continue
                traci.polygon.setParameter(obj.id, a, v)

        for objID in removeAtTime.get(t, []):
            traci.polygon.remove(objID)

        traci.simulationStep()
        t = traci.simulation.getTime()

    traci.close()


if __name__ == "__main__":
    main()
