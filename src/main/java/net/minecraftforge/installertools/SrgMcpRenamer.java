/*
 * InstallerTools
 * Copyright (c) 2019-2019.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.installertools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import de.siegmar.fastcsv.reader.CsvContainer;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRow;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraftforge.installertools.util.Utils;

public class SrgMcpRenamer extends Task {
    @Override
    public void process(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<File> mcpO = parser.accepts("mcp").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> inputO = parser.accepts("input").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> outputO = parser.accepts("output").withRequiredArg().ofType(File.class).required();
        OptionSpec<Boolean> stripSignatures0 = parser.accepts("strip-signatures").withRequiredArg().ofType(Boolean.class);

        try {
            OptionSet options = parser.parse(args);

            File mcp = options.valueOf(mcpO).getAbsoluteFile();
            File input = options.valueOf(inputO).getAbsoluteFile();
            File output = options.valueOf(outputO).getAbsoluteFile();
            Boolean stripSignaturesOption = options.valueOf(stripSignatures0);
            boolean stripSignatures = stripSignaturesOption == null? false : stripSignaturesOption;

            log("Input:  " + input);
            log("Output: " + output);
            log("MCP:    " + mcp);

            if (!mcp.exists())
                error("Missing required MCP data: " + mcp);
            if (!input.exists())
                error("Missing required input jar: " + input);
            if (output.exists()) output.delete();
            if (!output.getParentFile().exists()) output.getParentFile().mkdirs();
            output.createNewFile();

            log("Loading MCP Data");
            Map<String, String> map = new HashMap<>();
            try (ZipFile zip = new ZipFile(mcp)) {
                List<ZipEntry> entries = zip.stream().filter(e -> e.getName().endsWith(".csv")).collect(Collectors.toList());
                for (ZipEntry entry : entries) {
                    CsvReader reader = new CsvReader();
                    reader.setContainsHeader(true);
                    CsvContainer csv = reader.read(new InputStreamReader(zip.getInputStream(entry)));
                    for (CsvRow row : csv.getRows()) {
                        String searge = row.getField("searge");
                        if (searge == null)
                            searge = row.getField("param");
                        map.put(searge, row.getField("name"));
                    }
                }
            }

            Remapper remapper = new Remapper() {
                @Override
                public String mapFieldName(final String owner, final String name, final String descriptor) {
                    return map.getOrDefault(name, name);
                }
                @Override
                public String mapInvokeDynamicMethodName(final String name, final String descriptor) {
                    return map.getOrDefault(name, name);
                }
                @Override
                public String mapMethodName(final String owner, final String name, final String descriptor) {
                  return map.getOrDefault(name, name);
                }
            };

            ByteArrayOutputStream memory = input.equals(output) ? new ByteArrayOutputStream() : null;
            try (ZipOutputStream zout = new ZipOutputStream(memory == null ? new FileOutputStream(output) : memory);
                ZipInputStream zin = new ZipInputStream(new FileInputStream(input))) {
                ZipEntry ein = null;
                while ((ein = zin.getNextEntry()) != null) {
                    if (ein.getName().endsWith(".class")) {
                        byte[] data = Utils.toByteArray(zin);

                        ClassReader reader = new ClassReader(data);
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassRemapper(writer, remapper), 0);
                        data = writer.toByteArray();

                        ZipEntry eout = new ZipEntry(ein.getName());
                        eout.setTime(0x386D4380); //01/01/2000 00:00:00 java 8 breaks when using 0.
                        zout.putNextEntry(eout);
                        zout.write(data);
                    } else if (stripSignatures && (ein.getName().endsWith(".SF") || ein.getName().endsWith(".RSA"))) {
                        log("Stripped signature entry data " + ein.getName());
                    } else if (stripSignatures && ein.getName().endsWith("MANIFEST.MF")) {
                        Manifest min = new Manifest(zin);
                        Manifest mout = new Manifest();
                        mout.getMainAttributes().putAll(min.getMainAttributes());
                        min.getEntries().forEach((name, ain) -> {
                            final Attributes aout = new Attributes();
                            ain.forEach((k, v) -> {
                                if (!"SHA-256-Digest".equalsIgnoreCase(k.toString())) {
                                    aout.put(k, v);
                                }
                            });
                            if (!aout.values().isEmpty()) {
                                mout.getEntries().put(name, aout);
                            }
                        });

                        ZipEntry eout = new ZipEntry(ein.getName());
                        eout.setTime(0x386D4380); //01/01/2000 00:00:00 java 8 breaks when using 0.
                        zout.putNextEntry(eout);
                        mout.write(zout);
                        log("Stripped Manifest of sha digests");
                    } else {
                        zout.putNextEntry(ein);
                        Utils.copy(zin, zout);
                    }
                }
            }

            if (memory != null)
                Files.write(output.toPath(), memory.toByteArray());

            log("Process complete");
        } catch (OptionException e) {
            parser.printHelpOn(System.out);
            e.printStackTrace();
        }
    }
}
