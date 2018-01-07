package de.ofahrt.catfish.utils;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
  HttpFieldNameTest.class,
  ServletHelperTest.class,
  XsrfTokenTest.class,
})
public class CatfishUtilsTestSuite {
// Ok
}
