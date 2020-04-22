package org.apache.solomax;

import static org.apache.openmeetings.db.dao.label.LabelDao.getLabelFileName;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.core.Form;
import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.openmeetings.db.entity.label.StringLabel;
import org.apache.openmeetings.util.XmlExport;
import org.apache.wicket.util.string.Strings;
import org.dom4j.Document;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

/**
 * Hello world!
 *
 */
public class App {
	private static final String ENTRY_ELEMENT = "entry"; //FIXME TODO make it public in LabelDao
	private static final String KEY_ATTR = "key"; //FIXME TODO make it public in LabelDao
	private static final String API_BASE = "https://api.poeditor.com/v2/";
	private static final String PROJECT_ID = "333773";
	private static final String MODE_PUT = "put";
	public static final long TIMEOUT = 5 * 60 * 1000;
	private static Logger log = LoggerFactory.getLogger(App.class);

	private static JSONObject post(String path, String token, Form params) {
		WebClient c = WebClient.create(API_BASE)
				.accept("application/json");
		HTTPClientPolicy p = WebClient.getConfig(c).getHttpConduit().getClient();
		p.setConnectionTimeout(TIMEOUT);
		p.setReceiveTimeout(TIMEOUT);
		Response resp = c.path(path)
				.post(params.param("id", PROJECT_ID).param("api_token", token));

		String jsonStr = resp.readEntity(String.class);
		if (200 != resp.getStatus()) {
			log.error("Fail to call {} -> {}", path, jsonStr);
			System.exit(1);
		}
		return new JSONObject(jsonStr).getJSONObject("result");
	}

	private static Properties load(Path folder, String fileName) {
		Properties props = new Properties();
		try (FileInputStream is = new FileInputStream(folder.resolve(fileName).toFile())) {
			props.loadFromXML(is);
		} catch (Exception e) {
			log.error("Unexpected error while reading properties", e);
		}
		return props;
	}

	private static Properties load(String token, String code) throws Exception {
		JSONObject translation = post("projects/export", token, new Form()
				.param("language", code)
				.param("type", "properties"));
		URL url = new URL(translation.getString("url"));
		try (InputStream is = url.openStream(); Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
			Properties props = new Properties();
			props.load(r);
			return props;
		} catch (Exception e) {
			log.error("Unexpected error while reading properties", e);
			System.exit(1);
		}
		return null;
	}

	private static void save(Path path, String inCode, Properties lang, Properties eng) throws Exception {
		List<StringLabel> labels = new ArrayList<>();
		eng.forEach((inKey, value) -> {
			String key = (String)inKey;
			if (Strings.isEmpty(lang.getProperty(key))) {
				labels.add(new StringLabel(key, (String)value));
			} else {
				labels.add(new StringLabel(key, lang.getProperty(key)));
			}
		});
		String code = inCode;
		if ("zh-Hans".equals(inCode)) {
			code = "zh-CN";
		} else if ("zh-Hant".equals(inCode)) {
			code = "zh-TW";
		}
		Locale l = Locale.forLanguageTag(code);
		Document d = XmlExport.createDocument();
		Element r = XmlExport.createRoot(d);
		Collections.sort(labels, (o1, o2) -> {
			int val;
			try {
				int i1 = Integer.parseInt(o1.getKey()), i2 = Integer.parseInt(o2.getKey());
				val = i1 - i2;
			} catch (Exception e) {
				val = o1.getKey().compareTo(o2.getKey());
			}
			return val;
		});
		for (StringLabel sl : labels) {
			r.addElement(ENTRY_ELEMENT).addAttribute(KEY_ATTR, sl.getKey()).addCDATA(sl.getValue());
		}
		XmlExport.toXml(path.resolve(getLabelFileName(l)).toFile(), d);
	}

	public static void main(String[] args) throws Exception {
		System.out.println("Usage: .token om-src-root mode");
		if (args.length < 2) {
			return;
		}
		String token = Files.readString(Path.of(args[0])).trim();

		Path srcRoot = Path.of(args[1], "openmeetings-web/src/main/java/org/apache/openmeetings/web/app");
		if (args.length > 2 && MODE_PUT.equalsIgnoreCase(args[2])) {
			String engName = "Application.properties.xml";
			Properties eng = load(srcRoot, engName);
			Files.list(srcRoot)
				.filter(path -> path.toString().endsWith(".xml"))
				.forEach(path -> {
					String fileName = path.getFileName().toString();
					Properties props = load(srcRoot, fileName);
					String outFileName = fileName.substring(0, fileName.length() - 4);
					Path outPath = Path.of(args[2], outFileName);
					try (FileWriter fw = new FileWriter(outPath.toFile(), StandardCharsets.UTF_8, false))
					{
						if (fileName != engName) {
							for (Iterator<Map.Entry<Object, Object>> iter = props.entrySet().iterator(); iter.hasNext();) {
								Map.Entry<Object, Object> e = iter.next();
								if (eng.getProperty((String)e.getKey()).equals(e.getValue())) {
									iter.remove();
								}
							}
						}
						props.store(fw, "Apache OpenMeetings language file");
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
		} else {
			Properties langEng = load(token, "en");
			JSONObject json = post("languages/list", token, new Form());
			JSONArray languages = json.getJSONArray("languages");
			for (int i = 0; i < languages.length(); ++i) {
				JSONObject langJson = languages.getJSONObject(i);
				String code = langJson.getString("code");
				Properties lang = "en".equals(code) ? langEng : load(token, code);
				save(srcRoot, code, lang, langEng);
			}
		}
	}
}
