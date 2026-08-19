import org.yx6.Ym6Reader;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dumps a YM tune's registers as flat binary for {@code sweep.py}: format
 * (5 or 6), frame count and drum count as big-endian ints, then the sixteen
 * register vectors. The reader unpacks LHA archives by itself.
 */
public class DumpYm {
    public static void main(String[] args) throws Exception {
        var song = Ym6Reader.read(Files.readAllBytes(Path.of(args[0])));
        var out = new BufferedOutputStream(System.out);
        var data = new DataOutputStream(out);
        data.writeInt(song.format().startsWith("YM6") ? 6 : 5);
        data.writeInt(song.frames());
        data.writeInt(song.drums().length);
        for (int r = 0; r < 16; r++) {
            out.write(song.registers()[r], 0, song.frames());
        }
        out.flush();
    }
}
