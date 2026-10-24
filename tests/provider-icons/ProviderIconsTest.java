import android.graphics.Path;
import android.graphics.RectF;
import androidx.core.graphics.PathParser;
import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Run on Android: exercises the path parser and native geometry, not just aapt2. */
public final class ProviderIconsTest {
    public static void main(String[] args) {
        try {
            checkIcons(args[0]);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkIcons(String directory) throws Exception {
        File[] files = new File(directory).listFiles((dir, name) ->
                name.startsWith("ic_provider_") && name.endsWith(".xml"));
        if (files == null || files.length == 0) throw new AssertionError("No provider icons");
        int paths = 0;
        for (File file : files) {
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file).getElementsByTagName("path");
            for (int i = 0; i < nodes.getLength(); i++) {
                String data = ((Element) nodes.item(i)).getAttribute("android:pathData");
                try {
                    Path path = PathParser.createPathFromPathData(data);
                    RectF bounds = new RectF();
                    path.computeBounds(bounds, true);
                    if (bounds.isEmpty() || bounds.left < -1 || bounds.top < -1
                            || bounds.right > 25 || bounds.bottom > 25) {
                        throw new AssertionError("Invalid 24dp icon bounds: " + bounds);
                    }
                    paths++;
                } catch (RuntimeException e) {
                    throw new AssertionError(file.getName() + " path " + i, e);
                }
            }
        }
        System.out.println("ProviderIconsTest: " + files.length + " icons / " + paths
                + " paths parsed with valid geometry");
    }
}
