/**
 * Copyright (C) 2017 Julien Bonjean <julien@bonjean.info>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package info.bonjean.eclipse.easysync.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.internal.preferences.PreferencesService;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

@SuppressWarnings("restriction")
public class SyncHandler extends AbstractHandler {
	private static final String CONFIGURATION_FOLDER = String.format("%s/.config/eclipse-easysync",
			System.getProperty("user.home"));

	// where preferences are going to be saved
	private static final String PREFS_FILENAME = String.format("%s/preferences.db", CONFIGURATION_FOLDER);
	// where preferences config file is located
	private static final String PREFS_CONFIG_FILENAME = String.format("%s/preferences.config", CONFIGURATION_FOLDER);
	// where excluded preferences are going to be saved (easier for debug/config)
	private static final String PREFS_EXCLUDED_FILENAME = String.format("%s/preferences.excluded.db",
			System.getProperty("java.io.tmpdir"));

	private static final Pattern SECTION_PATTERN = Pattern.compile("^\\[(.+)\\]$");
	private static final Pattern ENTRY_PATTERN = Pattern.compile("^([^=]+)=(.*)$");

	// configuration keys used in preferences config file (PREFS_CONFIG_FILENAME)
	private static final String PREFS_CONFIG_EXCLUDE_LIST_KEY = "exclude.list";
	private static final String PREFS_CONFIG_EXCLUDE_PATTERN_KEY = "exclude.pattern";

	// base 64 encoder/decoder that we use to store multiline values
	private final Encoder b64Encoder = Base64.getEncoder();
	private final Decoder b64Decoder = Base64.getDecoder();

	private final ILog log = Activator.getDefault().getLog();
	private final IWorkbenchWindow window;

	public SyncHandler() {
		IWorkbench workbench = PlatformUI.getWorkbench();
		this.window = workbench.getActiveWorkbenchWindow();
	}

	private void logError(String message) {
		log.log(new Status(Status.ERROR, Activator.PLUGIN_ID, message));
	}

	private void writeProperties(Map<String, Map<String, String>> properties, String filename) throws IOException {
		PrintWriter writer = new PrintWriter(new FileWriter(filename));

		for (String sectionKey : properties.keySet()) {

			// write the section header
			writer.printf("[%s]\n", sectionKey);

			// write section keys/values
			Map<String, String> section = properties.get(sectionKey);
			for (String key : section.keySet()) {

				// ensure the key won't break our storage format
				if (key.contains("\n") || key.contains("=")) {
					logError("skipping key " + key + " (unsupported key)");
					continue;
				}

				String value = section.get(key);

				// multiline values are converted to base64
				if (value != null && value.contains("\n"))
					value = String.format("b64:%s", b64Encoder.encodeToString(value.getBytes("utf-8")));

				writer.printf("%s=%s\n", key, value);
			}
			writer.println();
		}
		writer.close();
	}

	private Map<String, Map<String, String>> readProperties(String filename) throws IOException {
		Map<String, Map<String, String>> properties = new TreeMap<>();
		BufferedReader reader = new BufferedReader(new FileReader(filename));

		Map<String, String> section = null;
		String sectionName = null;
		for (String line; (line = reader.readLine()) != null;) {

			// handle sections
			Matcher matcher = SECTION_PATTERN.matcher(line);
			if (matcher.find()) {

				// if we were reading a section before, store it
				if (sectionName != null && section != null && !section.isEmpty())
					properties.put(sectionName, section);

				// declare the new section
				sectionName = matcher.group(1);
				section = null;
				continue;
			}

			// handle preferences
			matcher = ENTRY_PATTERN.matcher(line);
			if (matcher.find()) {

				// if it is a new section, create the map
				if (section == null && sectionName != null)
					section = new TreeMap<>();

				// if the value is base64 encoded, decode it
				String value = matcher.group(2);
				if (value.startsWith("b64:"))
					value = new String(b64Decoder.decode(value.replaceFirst("^b64:", "")), "utf-8");

				section.put(matcher.group(1), value);
				continue;
			}

			// everything else is discarded
		}

		// store the last section
		if (sectionName != null && section != null && !section.isEmpty())
			properties.put(sectionName, section);

		reader.close();
		return properties;
	}

	private void exportPreferences(Map<String, Map<String, String>> preferencesConfig)
			throws BackingStoreException, IOException {
		// read preferences properties from the file
		// (we want to merge and not replace all the data, different installations can
		// have different plugins)
		Map<String, Map<String, String>> exportPreferences = null;
		if (new File(PREFS_FILENAME).exists())
			exportPreferences = readProperties(PREFS_FILENAME);
		else
			exportPreferences = new TreeMap<>();

		Map<String, Map<String, String>> excludedPreferences = new TreeMap<>();

		// eclipse preferences are stored in nodes, get the root node so we can iterate
		// on children
		Preferences preferences = PreferencesService.getDefault().getRootNode().node(InstanceScope.SCOPE);

		for (String preferencesNodeKey : preferences.childrenNames()) {

			// get the current node
			IEclipsePreferences preferencesNode = InstanceScope.INSTANCE.getNode(preferencesNodeKey);
			if (preferencesNode == null)
				continue;

			// load configuration for this node
			Map<String, String> preferenceNodeConfig = preferencesConfig.get(preferencesNodeKey);
			List<String> excludeList = getKeysExclusionList(preferenceNodeConfig);
			Pattern excludePattern = getKeysExclusionPattern(preferenceNodeConfig);

			// get the node preference keys and skip if empty
			String[] preferencesNodeKeys = preferencesNode.keys();
			if (preferencesNodeKeys.length == 0)
				continue;

			Map<String, String> exportPreferencesNode = null;
			if (exportPreferences.containsKey(preferencesNodeKey))
				exportPreferencesNode = exportPreferences.get(preferencesNodeKey);
			else
				exportPreferencesNode = new TreeMap<>();

			Map<String, String> excludedPreferencesNode = new TreeMap<>();

			// read the keys/values and store them in a map
			for (String key : preferencesNode.keys()) {

				// check the key against the exclusion list
				if (excludeList != null && excludeList.contains(key)) {
					excludedPreferencesNode.put(key, preferencesNode.get(key, null));
					exportPreferencesNode.remove(key);
					continue;
				}

				// check the key against the exclusion pattern
				if (excludePattern != null && excludePattern.matcher(key).matches()) {
					excludedPreferencesNode.put(key, preferencesNode.get(key, null));
					exportPreferencesNode.remove(key);
					continue;
				}

				// all good, we can store this preference
				exportPreferencesNode.put(key, preferencesNode.get(key, null));
			}

			// store the node map in the top level map
			if (!exportPreferencesNode.isEmpty())
				exportPreferences.put(preferencesNodeKey, exportPreferencesNode);

			// same for excluded properties
			if (!excludedPreferencesNode.isEmpty())
				excludedPreferences.put(preferencesNodeKey, excludedPreferencesNode);
		}

		// synchronize the configuration by creating the new placeholder if there are
		for (String exportPreferencesNodeKey : exportPreferences.keySet()) {
			Map<String, String> preferencesNodeConfig = preferencesConfig.get(exportPreferencesNodeKey);

			// if the section does not exist, create it
			if (preferencesNodeConfig == null) {
				preferencesNodeConfig = new TreeMap<>();
				preferencesConfig.put(exportPreferencesNodeKey, preferencesNodeConfig);
			}

			// create the keys with default value, so it will be easier to configure later
			if (!preferencesNodeConfig.containsKey(PREFS_CONFIG_EXCLUDE_PATTERN_KEY))
				preferencesNodeConfig.put(PREFS_CONFIG_EXCLUDE_PATTERN_KEY, "");
			if (!preferencesNodeConfig.containsKey(PREFS_CONFIG_EXCLUDE_LIST_KEY))
				preferencesNodeConfig.put(PREFS_CONFIG_EXCLUDE_LIST_KEY, "");
		}

		// write all the properties to the file
		writeProperties(exportPreferences, PREFS_FILENAME);

		// write all the excluded properties to the file
		writeProperties(excludedPreferences, PREFS_EXCLUDED_FILENAME);
	}

	private void resetDefaultPreferences() throws BackingStoreException {
		// while all preferences
		Preferences preferences = PreferencesService.getDefault().getRootNode().node(InstanceScope.SCOPE);

		for (String preferencesNodeKey : preferences.childrenNames()) {
			// get the current node
			IEclipsePreferences preferencesNode = InstanceScope.INSTANCE.getNode(preferencesNodeKey);
			if (preferencesNode == null)
				continue;

			// clear associated preferences
			preferencesNode.clear();
		}

		// save to disk
		preferences.flush();

		// delete preferences file
		File file = new File(PREFS_FILENAME);
		if (file.exists())
			file.delete();
	}

	private void importPreferences(Map<String, Map<String, String>> preferencesConfig)
			throws IOException, BackingStoreException {
		// read preferences properties from the file
		Map<String, Map<String, String>> exportedPreferences = readProperties(PREFS_FILENAME);

		// finally we clear preference nodes that were not in the properties
		Preferences preferences = PreferencesService.getDefault().getRootNode().node(InstanceScope.SCOPE);
		for (String preferencesNodeKey : preferences.childrenNames()) {
			IEclipsePreferences preferencesNode = InstanceScope.INSTANCE.getNode(preferencesNodeKey);
			if (preferencesNode == null)
				continue;

			if (!exportedPreferences.containsKey(preferencesNodeKey))
				continue;

			// load configuration for this node
			Map<String, String> preferencesNodeConfig = preferencesConfig.get(preferencesNodeKey);
			List<String> excludeList = getKeysExclusionList(preferencesNodeConfig);
			Pattern excludePattern = getKeysExclusionPattern(preferencesNodeConfig);

			// load stored data for this node
			Map<String, String> exportedPreferencesNode = exportedPreferences.get(preferencesNodeKey);

			// set the preferences
			for (Entry<String, String> exportedPreferencesNodeEntry : exportedPreferencesNode.entrySet()) {
				String key = exportedPreferencesNodeEntry.getKey();
				if (isExcludedKey(key, excludeList, excludePattern))
					continue;
				preferencesNode.put(key, exportedPreferencesNodeEntry.getValue());
			}

			// clear unset preferences
			for (String key : preferencesNode.keys()) {
				if (isExcludedKey(key, excludeList, excludePattern))
					continue;
				if (!exportedPreferencesNode.containsKey(key))
					preferencesNode.remove(key);
			}
		}

		// save to disk
		preferences.flush();

		// ensure everything is up-to-date
		preferences.sync();
	}

	private void savePreferencesConfig(Map<String, Map<String, String>> preferencesConfig) throws IOException {
		writeProperties(preferencesConfig, PREFS_CONFIG_FILENAME);
	}

	private List<String> getKeysExclusionList(Map<String, String> nodeConfig) {
		if (nodeConfig == null)
			return null;

		if (nodeConfig.containsKey(PREFS_CONFIG_EXCLUDE_LIST_KEY))
			return Arrays.asList(nodeConfig.get(PREFS_CONFIG_EXCLUDE_LIST_KEY).split(","));
		else
			return null;
	}

	private Pattern getKeysExclusionPattern(Map<String, String> nodeConfig) {
		if (nodeConfig == null)
			return null;

		if (!nodeConfig.containsKey(PREFS_CONFIG_EXCLUDE_PATTERN_KEY))
			return null;

		String pattern = nodeConfig.get(PREFS_CONFIG_EXCLUDE_PATTERN_KEY);
		if (!pattern.isEmpty())
			return null;

		// pre-compile the pattern
		return Pattern.compile(pattern);
	}

	private boolean isExcludedKey(String key, List<String> excludeList, Pattern excludePattern) {
		// check the key against the exclusion list
		if (excludeList != null && excludeList.contains(key))
			return true;

		// check the key against the exclusion pattern
		if (excludePattern != null && excludePattern.matcher(key).matches())
			return true;

		return false;
	}

	private Map<String, Map<String, String>> loadPreferencesConfig() throws IOException {
		// create configuration folder if required
		if (!new File(CONFIGURATION_FOLDER).exists())
			new File(CONFIGURATION_FOLDER).mkdirs();

		Map<String, Map<String, String>> preferencesConfig = null;
		if (new File(PREFS_CONFIG_FILENAME).exists())
			preferencesConfig = readProperties(PREFS_CONFIG_FILENAME);
		else
			preferencesConfig = new TreeMap<>();

		return preferencesConfig;
	}

	@Override
	public Object execute(ExecutionEvent event) {
		Map<String, Map<String, String>> preferencesConfig = null;
		try {
			switch (event.getCommand().getId()) {
			case "command.sync.export.id":

				// load preferences configuration (for exclusions, ...)
				preferencesConfig = loadPreferencesConfig();

				// extract preferences from Eclipse and save to filesystem
				exportPreferences(preferencesConfig);

				// write the preferences configuration to the file (new sections may have been
				// generated)
				savePreferencesConfig(preferencesConfig);

				MessageDialog.openInformation(window.getShell(), "Eclipse Sync",
						"Preferences successfully saved to " + PREFS_FILENAME);
				break;

			case "command.sync.import.id":

				// check if the properties file exists
				if (!new File(PREFS_FILENAME).exists()) {
					MessageDialog.openWarning(window.getShell(), "Eclipse Sync",
							"Preferences data file does not exist (" + PREFS_FILENAME + ")");
					return null;
				}

				// ask for confirmation before proceeding
				if (!MessageDialog.openConfirm(window.getShell(), "Eclipse Sync",
						"Eclipse preferences will be overridden by sync data." + "\nDo you want to continue?"))
					return null;

				// load preferences configuration (for exclusions, ...)
				preferencesConfig = loadPreferencesConfig();

				// load preferences from the filesystem
				importPreferences(preferencesConfig);

				MessageDialog.openInformation(window.getShell(), "Eclipse Sync",
						"Preferences successfully loaded from " + PREFS_FILENAME + ", Eclipse will now be restarted");

				// restart Eclipse
				PlatformUI.getWorkbench().restart(true);

				break;

			case "command.sync.edit_config.id":

				// open an editor with the config content
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				IFileStore fileStore = EFS.getLocalFileSystem().getStore(new File(PREFS_CONFIG_FILENAME).toURI());
				IDE.openInternalEditorOnFileStore(page, fileStore);
				break;

			case "command.sync.reset.id":

				// ask for confirmation before proceeding
				if (!MessageDialog.openConfirm(window.getShell(), "Eclipse Sync",
						"Eclipse preferences and sync data will be reset to default (wiped)."
								+ "You may also encounter errors during the process, this is expected."
								+ "\nEclipse will be restarted." + "\nDo you want to continue?"))
					return null;

				// reset preferences to default
				resetDefaultPreferences();

				// restart Eclipse
				PlatformUI.getWorkbench().restart(true);

				break;

			default:
				logError("unknown sync action: " + event.getCommand().getId());
			}

		} catch (Exception e) {
			// we don't try to properly handle exceptions, we just catch everything here
			e.printStackTrace();
			StringWriter stringWriter = new StringWriter();
			e.printStackTrace(new PrintWriter(stringWriter));
			MessageDialog.openError(window.getShell(), "Eclipse Sync",
					"An error occurred:\n" + stringWriter.toString());
		}

		return null;
	}
}
