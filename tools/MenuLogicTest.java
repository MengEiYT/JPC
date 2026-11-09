import java.nio.file.*;
import java.util.*;
import com.jpc.vm.*;
import com.jpc.vm.IsoLibrary.*;

public class MenuLogicTest {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "  ok   " : "  FAIL ") + name); if (!ok) fails++; }
    static void write(Path p, long size) throws Exception { try (var f = new java.io.RandomAccessFile(p.toFile(), "rw")) { f.setLength(size); } }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("isos").resolve("isos");
        check("folder does not exist yet", !Files.exists(dir));
        IsoLibrary.prepareFolder(dir);
        check("prepareFolder creates folder + note", Files.isDirectory(dir) && Files.exists(dir.resolve("put-your-iso-files-here.txt")));
        Files.delete(dir.resolve("put-your-iso-files-here.txt"));
        IsoLibrary.prepareFolder(dir);
        check("existing folder is left alone (note not recreated)", !Files.exists(dir.resolve("put-your-iso-files-here.txt")));

        write(dir.resolve("Alpine.ISO"), 58L << 20);
        write(dir.resolve("kolibri.iso"), 70L << 20);
        write(dir.resolve("boot.img"), 1_474_560);
        write(dir.resolve("big.img"), 64L << 20);
        write(dir.resolve("data.hdd"), 10L << 20);
        write(dir.resolve("notes.txt"), 100);
        write(dir.resolve("empty.iso"), 0);
        write(dir.resolve(".hidden.iso"), 100);
        Files.createDirectory(dir.resolve("sub.iso"));
        List<Entry> list = IsoLibrary.scan(dir);
        System.out.println("  scan: " + list.stream().map(Entry::label).toList());
        check("finds 5 images (ignores txt, empty file, hidden file, folder)", list.size() == 5);
        check("CDs first, sorted ignoring case", list.get(0).name().equals("Alpine.ISO") && list.get(1).name().equals("kolibri.iso"));
        check("small .img is a floppy", list.stream().anyMatch(e -> e.name().equals("boot.img") && e.kind() == Kind.FLOPPY));
        check("big .img is a disk", list.stream().anyMatch(e -> e.name().equals("big.img") && e.kind() == Kind.DISK));
        check("size text", IsoLibrary.humanSize(1_474_560).equals("1.4 MB") && IsoLibrary.humanSize(500).equals("1 KB") && IsoLibrary.humanSize(812L * 1024).equals("812 KB") && IsoLibrary.humanSize(58L << 20).equals("58 MB") && IsoLibrary.humanSize(3L << 29).equals("1.5 GB"));
        check("label", list.get(0).label().equals("Alpine.ISO  (CD, 58 MB)"));
        check("missing folder scans as empty", IsoLibrary.scan(dir.resolve("nope")).isEmpty());

        Path d1 = IsoLibrary.createBlankDisk(dir, 256);
        Path d2 = IsoLibrary.createBlankDisk(dir, 128);
        check("blank disks get new names", d1.getFileName().toString().equals("disk1.hdd") && d2.getFileName().toString().equals("disk2.hdd"));
        check("blank disk has the right size", Files.size(d1) == 256L << 20 && Files.size(d2) == 128L << 20);
        boolean threw = false; try { IsoLibrary.createBlankDisk(dir, 0); } catch (java.io.IOException e) { threw = true; }
        check("bad disk size rejected", threw);

        // ---- BootSetup
        list = IsoLibrary.scan(dir);   // 7 entries now
        BootSetup s = new BootSetup(300, 128, 100);
        s.setRowsPerPage(3);
        s.setEntries(list, null, null);
        check("first entry preselected", s.selected() != null && s.selected().name().equals("Alpine.ISO"));
        check("pages: 7 entries / 3 rows = 3 pages", s.pageCount() == 3);
        s.nextPage(); s.nextPage(); s.nextPage();
        check("next page stops at the last", s.page() == 2);
        check("row beyond the list is empty", s.rowEntry(1) == null && s.rowEntry(0) != null);
        s.prevPage(); s.prevPage(); s.prevPage();
        check("prev page stops at 0", s.page() == 0);
        s.pickRow(1);
        check("pickRow selects", s.selected().name().equals("kolibri.iso"));
        Path kolibri = s.selected().path();

        // RAM: max 300 -> options 16..256
        check("max RAM follows the game's memory", s.maxRamMb() == 256);
        s.cycleRam(); check("128 -> 256", s.ramMb() == 256);
        s.cycleRam(); check("256 -> wraps to 16", s.ramMb() == 16);
        BootSetup tiny = new BootSetup(10, 64, 100);
        check("tiny memory still offers 16 MB", tiny.ramMb() == 16 && tiny.maxRamMb() == 16);
        BootSetup odd = new BootSetup(1024, 100, 100); odd.cycleRam();
        check("odd RAM value cycles to the next step", odd.ramMb() == 128);

        s.cycleCpu(); check("cpu 100 -> 80", s.cpuPercent() == 80);
        s.cycleCpu(); s.cycleCpu(); s.cycleCpu(); check("-> 25", s.cpuPercent() == 25);
        s.cycleCpu(); check("25 -> wraps to 100", s.cpuPercent() == 100);

        // disks: big.img, data.hdd, disk1.hdd, disk2.hdd are DISK kinds
        check("4 disk choices while booting a CD", s.diskChoices().size() == 4);
        check("no extra disk at first", s.disk() == null);
        s.cycleDisk(); check("cycle -> first disk", s.disk() != null);
        for (int i = 0; i < 3; i++) s.cycleDisk();
        check("cycling reaches the last disk", s.disk() != null && s.disk().equals(s.diskChoices().get(3)));
        s.cycleDisk(); check("then none again", s.disk() == null);
        s.attach(d1); check("attach by path", s.disk() != null && s.disk().path().equals(d1));
        BootSetup.Launch l = s.launch();
        check("launch carries pick, disk, ram, cpu", l.boot().name().equals("kolibri.iso") && l.disk().path().equals(d1) && l.ramMb() == 16 && l.cpuPercent() == 100);

        // picking the attached disk as boot item detaches it
        int row = -1; s.setRowsPerPage(50);
        for (int i = 0; i < s.entries().size(); i++) if (s.entries().get(i).path().equals(d1)) row = i;
        s.pickRow(row);
        check("booting a disk detaches it as extra", s.disk() == null && s.selected().path().equals(d1));
        check("launch of a disk has no extra disk", s.launch().disk() == null);
        check("boot disk is not offered as extra", s.diskChoices().stream().noneMatch(e -> e.path().equals(d1)));
        s.cycleNewDiskSize(); check("new disk size 512 -> 1024", s.newDiskMb() == 1024);
        for (int i = 0; i < 2; i++) s.cycleNewDiskSize(); check("... wraps to 128", s.newDiskMb() == 128);

        // rescan keeps the pick by path, drops vanished files
        s.setEntries(IsoLibrary.scan(dir), null, null);
        check("rescan keeps the pick", s.selected().path().equals(d1));
        Files.delete(d1);
        s.setEntries(IsoLibrary.scan(dir), null, null);
        check("pick gone -> first entry", s.selected() != null && s.selected().name().equals("Alpine.ISO"));
        BootSetup empty = new BootSetup(256, 64, 100);
        empty.setEntries(List.of(), null, null);
        check("empty list: nothing to start", !empty.canStart() && empty.launch() == null && empty.pageCount() == 1);
        // preselect from config
        s.setEntries(IsoLibrary.scan(dir), kolibri, d2);
        check("preselect from config", s.selected().path().equals(kolibri) && s.disk() != null && s.disk().path().equals(d2));

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
