package lombok;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ java.lang.annotation.ElementType.TYPE })
@Retention(RetentionPolicy.SOURCE)
public @interface InternationlCode {
	public abstract String path() default "default.properties";
}
