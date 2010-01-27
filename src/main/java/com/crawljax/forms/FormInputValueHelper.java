package com.crawljax.forms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPathExpressionException;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.RenderedWebElement;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.crawljax.browser.AbstractWebDriver;
import com.crawljax.browser.EmbeddedBrowser;
import com.crawljax.condition.eventablecondition.EventableCondition;
import com.crawljax.core.CandidateElement;
import com.crawljax.core.configuration.InputSpecificationReader;
import com.crawljax.util.Helper;
import com.crawljax.util.PropertyHelper;
import com.crawljax.util.XPathHelper;

/**
 * Helper class for FormHandler.
 * 
 * @author dannyroest@gmail.com (Danny Roest)
 * @version $Id$
 */
public final class FormInputValueHelper {

	private static final int RANDOM_STRING_LENGTH = 8;

	private FormInputValueHelper() {

	}

	private static final Logger LOGGER = Logger.getLogger(FormInputValueHelper.class.getName());

	private static Map<String, String> formFields = new HashMap<String, String>();
	private static Map<String, ArrayList<String>> formFieldNames =
	        new HashMap<String, ArrayList<String>>();
	private static Map<String, ArrayList<String>> fieldValues =
	        new HashMap<String, ArrayList<String>>();

	private static Configuration config;

	static {
		try {
			readProperties();
		} catch (ConfigurationException e) {
			LOGGER.error("Could not read forms properties file", e);
		}
	}

	/**
	 * Reads the configuration.
	 * 
	 * @throws ConfigurationException
	 *             when cannot read the configuration
	 */
	@SuppressWarnings("unchecked")
	public static void readProperties() throws ConfigurationException {
		if (config == null) {
			if (PropertyHelper.getCrawljaxConfiguration() == null) {
				config = new PropertiesConfiguration(PropertyHelper.getFormPropertiesValue());
			} else {
				config =
				        new InputSpecificationReader(PropertyHelper.getCrawljaxConfiguration()
				                .getInputSpecification()).getConfiguration();
			}
			Iterator keyIterator = config.getKeys();
			while (keyIterator.hasNext()) {
				String fieldInfo = keyIterator.next().toString();
				String id = fieldInfo.split("\\.")[0];
				String property = fieldInfo.split("\\.")[1];
				if (property.equalsIgnoreCase("fields")) {
					if (!formFields.containsKey(id)) {
						for (String fieldName : getPropertyAsList(fieldInfo)) {
							formFields.put(fieldName, id);
						}
						formFieldNames.put(id, getPropertyAsList(fieldInfo));
					}
				}
				if (property.equalsIgnoreCase("values")) {
					fieldValues.put(id, getPropertyAsList(fieldInfo));
				}
			}
		}
	}

	private static Element getBelongingElement(Document dom, String fieldName) {
		List<String> names = getNamesForInputFieldId(fieldName);
		if (names != null) {
			for (String name : names) {
				String xpath = "//*[@name='" + name + "' or @id='" + name + "']";
				try {
					Node node = Helper.getElementByXpath(dom, xpath);
					if (node != null) {
						return (Element) node;
					}
				} catch (XPathExpressionException e) {
					LOGGER.debug(e);
					// just try next
				}
			}
		}
		return null;
	}

	private static int getMaxNumberOfValues(List<String> fieldNames) {
		int maxValues = 0;
		// check the maximum number of form inputValues
		for (String fieldName : fieldNames) {
			List<String> values = getValuesForName(fieldName);
			if (values != null && values.size() > maxValues) {
				maxValues = values.size();
			}
		}
		return maxValues;
	}

	/**
	 * @param browser
	 *            the browser instance
	 * @param sourceElement
	 *            the form elements
	 * @param eventableCondition
	 *            the belonging eventable condition for sourceElement
	 * @return a list with Candidate elements for the inputs
	 */
	public static List<CandidateElement> getCandidateElementsForInputs(EmbeddedBrowser browser,
	        Element sourceElement, EventableCondition eventableCondition) {
		List<CandidateElement> candidateElements = new ArrayList<CandidateElement>();
		int maxValues = getMaxNumberOfValues(eventableCondition.getLinkedInputFields());

		if (maxValues == 0) {
			LOGGER.warn("No input values found for element: "
			        + Helper.getElementString(sourceElement));
			return candidateElements;
		}

		Document dom;
		try {
			dom = Helper.getDocument(browser.getDomWithoutIframeContent());
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			return candidateElements;
		}

		// add maxValues Candidate Elements for every input combination
		for (int curValueIndex = 0; curValueIndex < maxValues; curValueIndex++) {
			List<FormInput> formInputsForCurrentIndex = new ArrayList<FormInput>();
			for (String fieldName : eventableCondition.getLinkedInputFields()) {
				Element element = getBelongingElement(dom, fieldName);
				if (element != null) {
					FormInput formInput =
					        getFormInputWithIndexValue(browser, element, curValueIndex);
					formInputsForCurrentIndex.add(formInput);
				} else {
					LOGGER.warn("Could not find input element for: " + fieldName);
				}
			}

			String id = eventableCondition.getId() + "_" + curValueIndex;
			sourceElement.setAttribute("atusa", id);

			// clone node inclusive text content
			Element cloneElement = (Element) sourceElement.cloneNode(false);
			cloneElement.setTextContent(Helper.getTextValue(sourceElement));

			CandidateElement candidateElement =
			        new CandidateElement(cloneElement, XPathHelper
			                .getXpathExpression(sourceElement));
			candidateElement.setFormInputs(formInputsForCurrentIndex);
			candidateElements.add(candidateElement);
		}
		return candidateElements;
	}

	/**
	 * @param input
	 *            the form input
	 * @param dom
	 *            the document
	 * @return returns the belonging node to input in dom
	 */
	public static Node getBelongingNode(FormInput input, Document dom) {
		String xpath = "";
		String element = "";
		try {
			if (input.getType().equalsIgnoreCase("select")
			        || input.getType().equalsIgnoreCase("textarea")) {
				element = input.getType().toUpperCase();
			} else {
				element = "INPUT";
			}
			xpath =
			        "//" + element + "[@name='" + input.getName() + "' or @id='"
			                + input.getName() + "']";
			Node node = Helper.getElementByXpath(dom, xpath);

			if (node == null) {
				LOGGER.info("Cannot find element " + element + " id/name: " + input.getName());
			}
			return node;
		} catch (XPathExpressionException e) {
			LOGGER.error("Error with xpath: " + xpath + " for element " + element + " id/name: "
			        + input.getName(), e);
			return null;
		}
	}

	/**
	 * @param element
	 * @return returns the id of the element if set, else the name. If none found, returns null
	 * @throws Exception
	 */
	private static String getName(Node element) throws Exception {
		NamedNodeMap attributes = element.getAttributes();
		if (attributes.getNamedItem("id") != null) {
			return attributes.getNamedItem("id").getNodeValue();
		} else if (attributes.getNamedItem("name") != null) {
			return attributes.getNamedItem("name").getNodeValue();
		}
		return null;
	}

	/**
	 * @param browser
	 *            the current browser instance
	 * @param element
	 *            the element in the dom
	 * @return the first related formInput belonging to element in the browser
	 */
	public static FormInput getFormInputWithDefaultValue(EmbeddedBrowser browser, Node element) {
		return getFormInput(browser, element, 0);
	}

	/**
	 * @param browser
	 *            the current browser instance
	 * @param element
	 *            the element in the dom
	 * @param indexValue
	 *            the i-th specified value. if i>#values, first value is used
	 * @return the specified value with index indexValue for the belonging elements
	 */
	public static FormInput getFormInputWithIndexValue(EmbeddedBrowser browser, Node element,
	        int indexValue) {
		return getFormInput(browser, element, indexValue);
	}

	private static FormInput getFormInput(EmbeddedBrowser browser, Node element, int indexValue) {
		String name;
		try {
			name = getName(element);
			if (name == null) {
				return null;
			}
		} catch (Exception e) {
			return null;
		}
		String id = FormInputValueHelper.fieldMatches(name);
		FormInput input = new FormInput();
		input.setType(getElementType(element));
		input.setName(name);
		Set<InputValue> values = new HashSet<InputValue>();

		if (id != null && fieldValues.containsKey(id)) {
			// TODO: make multiple selection for list available
			// add defined value to element
			String value;
			if (indexValue == 0 || fieldValues.get(id).size() == 1
			        || indexValue + 1 > fieldValues.get(id).size()) {
				// default value
				value = fieldValues.get(id).get(0);
			} else if (indexValue > 0) {
				// index value
				value = fieldValues.get(id).get(indexValue);
			} else {
				// random value
				value =
				        fieldValues.get(id).get(
				                new Random().nextInt(fieldValues.get(id).size() - 1));
			}

			if (input.getType().equals("checkbox") || input.getType().equals("radio")) {
				// check element
				values.add(new InputValue(value, value.equals("1")));
			} else {
				// set value of text input field
				values.add(new InputValue(value, true));
			}

			input.setInputValues(values);
		} else {
			// field is not specified, lets try a random value
			return getInputWithRandomValue(browser, input, element);

		}
		return input;
	}

	/**
	 * TODO: make not webdriver dependent? TODO: add settings for default random values
	 * 
	 * @param browser
	 * @param input
	 * @param element
	 * @return FormInput with random value assign if possible
	 */
	private static FormInput getInputWithRandomValue(EmbeddedBrowser browser, FormInput input,
	        Node element) {
		if (!PropertyHelper.getCrawlFormWithRandomValues()) {
			return null;
		}

		if (browser instanceof AbstractWebDriver) {
			WebDriver driver = ((AbstractWebDriver) browser).getDriver();

			WebElement webElement;
			try {
				webElement =
				        driver.findElement(By.xpath(XPathHelper.getXpathExpression(element)));
				if (!((RenderedWebElement) webElement).isDisplayed()) {
					return null;
				}
			} catch (Exception e) {
				return null;
			}
			Set<InputValue> values = new HashSet<InputValue>();

			// create some random value
			// if(input.getType().toLowerCase().startsWith("text")/* &&
			// webElement.getValue().equals("")*/){
			if (input.getType().toLowerCase().startsWith("text")) {
				values.add(new InputValue(new RandomInputValueGenerator()
				        .getRandomString(RANDOM_STRING_LENGTH), true));
			} else if (input.getType().equalsIgnoreCase("checkbox")
			        || input.getType().equalsIgnoreCase("radio") && !webElement.isSelected()) {
				if (new RandomInputValueGenerator().getCheck()) {
					values.add(new InputValue("1", true));
				} else {
					values.add(new InputValue("0", false));

				}
			} else if (input.getType().equalsIgnoreCase("select")) {
				try {
					Select select = new Select(webElement);
					WebElement option =
					        (WebElement) new RandomInputValueGenerator().getRandomOption(select
					                .getOptions());
					values.add(new InputValue(option.getText(), true));
				} catch (Exception e) {
					LOGGER.error(e.getMessage(), e);
					return null;
				}
			}

			if (values.size() == 0) {
				return null;
			}
			input.setInputValues(values);
			return input;
		}

		return null;
	}

	/**
	 * @param property
	 *            the property.
	 * @return the values as a List.
	 */
	private static ArrayList<String> getPropertyAsList(String property) {
		ArrayList<String> result = new ArrayList<String>();
		String[] array = config.getStringArray(property);
		for (int i = 0; i < array.length; i++) {
			result.add(array[i]);
		}
		return result;
	}

	private static String fieldMatches(String fieldName) {
		for (String field : formFields.keySet()) {
			Pattern p = Pattern.compile(field, Pattern.CASE_INSENSITIVE);
			Matcher m = p.matcher(fieldName);
			if (m.matches()) {
				return formFields.get(field);
			}
		}
		return null;
	}

	private static List<String> getValuesForName(String inputFieldId) {
		if (!fieldValues.containsKey(inputFieldId)) {
			return null;
		}
		return fieldValues.get(inputFieldId);
	}

	private static List<String> getNamesForInputFieldId(String inputFieldId) {
		if (!formFieldNames.containsKey(inputFieldId)) {
			return null;
		}
		return formFieldNames.get(inputFieldId);
	}

	private static String getElementType(Node node) {
		if (node.getAttributes().getNamedItem("type") != null) {
			return node.getAttributes().getNamedItem("type").getNodeValue().toLowerCase();
		} else if (node.getNodeName().equalsIgnoreCase("input")) {
			return "text";
		} else {
			return node.getNodeName().toLowerCase();
		}
	}

}
