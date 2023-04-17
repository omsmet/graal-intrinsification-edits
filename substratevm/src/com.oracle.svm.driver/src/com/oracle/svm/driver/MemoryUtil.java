/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.driver;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.oracle.svm.core.OS;

class MemoryUtil {
    private static final double MAX_RAM_MIN_PERCENTAGE = 50.0;
    private static final double MAX_RAM_MAX_PERCENTAGE = 80.0;
    private static final double MAX_RAM_HEADROOM_PERCENTAGE = 10.0;

    private static final Pattern MEMINFO_AVAILABLE_REGEX = Pattern.compile("^MemAvailable:\\s+(\\d+) kB");

    public static double determineMaxRAMPercentage() {
        double availableRAMPercentage = Math.floor(getAvailableRAMPercentage() - MAX_RAM_HEADROOM_PERCENTAGE);
        return Math.max(MAX_RAM_MIN_PERCENTAGE, Math.min(availableRAMPercentage, MAX_RAM_MAX_PERCENTAGE));
    }

    private static double getAvailableRAMPercentage() {
        var osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double usableMemory = osBean.getFreeMemorySize();
        // TODO: drop debug code
        System.out.println("Free memory percentage: " + (usableMemory / osBean.getTotalMemorySize()));
        if (OS.LINUX.isCurrent()) {
            // Increase value if more memory is available on Linux.
            usableMemory = Math.max(usableMemory, getLinuxAvailableMemory());
            // TODO: drop debug code
            System.out.println("Available memory percentage: " + (usableMemory / osBean.getTotalMemorySize()));
        }
        return (usableMemory / osBean.getTotalMemorySize()) * 100;
    }

    private static long getLinuxAvailableMemory() {
        try {
            String memAvailableLine = Files.readAllLines(Paths.get("/proc/meminfo")).stream().filter(l -> l.startsWith("MemAvailable")).findFirst().orElse("");
            Matcher m = MEMINFO_AVAILABLE_REGEX.matcher(memAvailableLine);
            if (m.matches()) {
                return Long.parseLong(m.group(1)) * 1024 /* kB to B */;
            }
        } catch (Exception e) {
        }
        return -1;
    }
}
