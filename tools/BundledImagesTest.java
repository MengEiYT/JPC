import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import com.jpc.vm.*;
import com.jpc.vm.BundledImages.Image;

/** Checks the "ship KolibriOS in the images folder" logic without Minecraft. */
public class BundledImagesTest {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "  ok   " : "  FAIL ") + name); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        byte[] data = new byte[1_474_560];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        Image kolibri = new Image("/jpc/bundled/kolibri.img", "KolibriOS.img");
        var jar = (java.util.function.Function<String, InputStream>) r ->
                r.equals(kolibri.resource()) ? new ByteArrayInputStream(data) : null;
        var emptyJar = (java.util.function.Function<String, InputStream>) r -> null;
        List<Image> known = List.of(kolibri);

        Path dir = Files.createTempDirectory("bundled").resolve("isos");
        IsoLibrary.prepareFolder(dir);

        check("image missing from the jar: nothing happens", BundledImages.install(dir, known, emptyJar).isEmpty()
                && !Files.exists(dir.resolve("KolibriOS.img")) && !Files.exists(dir.resolve(BundledImages.MARKER)));
        check("the real mod jar without the file is a no-op too", BundledImages.install(dir).isEmpty());

        List<String> first = BundledImages.install(dir, known, jar);
        check("first run copies it", first.equals(List.of("KolibriOS.img")));
        check("copy is byte for byte", Arrays.equals(Files.readAllBytes(dir.resolve("KolibriOS.img")), data));
        check("no .part file is left", !Files.exists(dir.resolve("KolibriOS.img.part")));
        List<IsoLibrary.Entry> list = IsoLibrary.scan(dir);
        check("the boot menu lists it as a floppy", list.size() == 1 && list.get(0).kind() == IsoLibrary.Kind.FLOPPY
                && list.get(0).label().equals("KolibriOS.img  (Floppy, 1.4 MB)"));
        check("marker is hidden, so it is not listed", list.stream().noneMatch(e -> e.name().startsWith(".")));

        check("second run copies nothing", BundledImages.install(dir, known, jar).isEmpty());

        Files.delete(dir.resolve("KolibriOS.img"));
        check("a deleted image does not come back", BundledImages.install(dir, known, jar).isEmpty()
                && !Files.exists(dir.resolve("KolibriOS.img")));

        Path dir2 = Files.createTempDirectory("bundled2").resolve("isos");
        Files.createDirectories(dir2);
        byte[] mine = "my own file".getBytes();
        Files.write(dir2.resolve("KolibriOS.img"), mine);
        check("a file the player already has is not overwritten", BundledImages.install(dir2, known, jar).isEmpty()
                && Arrays.equals(Files.readAllBytes(dir2.resolve("KolibriOS.img")), mine));

        Path dir3 = Files.createTempDirectory("bundled3").resolve("not-yet");
        check("folder is created when missing", BundledImages.install(dir3, known, jar).size() == 1 && Files.isDirectory(dir3));

        var broken = (java.util.function.Function<String, InputStream>) r -> new InputStream() {
            int n = 0;
            public int read() throws java.io.IOException { if (n++ > 100) throw new java.io.IOException("disk full"); return 7; }
        };
        Path dir4 = Files.createTempDirectory("bundled4").resolve("isos");
        check("a failed copy installs nothing", BundledImages.install(dir4, known, broken).isEmpty()
                && !Files.exists(dir4.resolve("KolibriOS.img")) && !Files.exists(dir4.resolve("KolibriOS.img.part")));
        check("...and is retried next start", BundledImages.install(dir4, known, jar).equals(List.of("KolibriOS.img")));

        Path dir5 = Files.createTempDirectory("bundled5").resolve("isos");
        check("an empty resource installs nothing", BundledImages.install(dir5, known, r -> new ByteArrayInputStream(new byte[0])).isEmpty()
                && !Files.exists(dir5.resolve("KolibriOS.img")));
        check("null folder is ignored", BundledImages.install(null, known, jar).isEmpty());

        // The marker from the IsoCraft days: an image listed there does not come back, and the marker moves to the new name.
        Path dir6 = Files.createTempDirectory("bundled6").resolve("isos");
        Files.createDirectories(dir6);
        Files.writeString(dir6.resolve(BundledImages.OLD_MARKER), "KolibriOS.img\n");
        check("an image listed in the old IsoCraft marker is not installed again", BundledImages.install(dir6, known, jar).isEmpty()
                && !Files.exists(dir6.resolve("KolibriOS.img")));
        check("...and the old marker moves to the new name", Files.isRegularFile(dir6.resolve(BundledImages.MARKER))
                && !Files.exists(dir6.resolve(BundledImages.OLD_MARKER))
                && Files.readString(dir6.resolve(BundledImages.MARKER)).contains("KolibriOS.img"));

        check("the real image list has KolibriOS.img", BundledImages.KNOWN.stream().anyMatch(i -> i.fileName().equals("KolibriOS.img")));

        System.out.println(fails == 0 ? "BundledImagesTest: all ok" : "BundledImagesTest: " + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
