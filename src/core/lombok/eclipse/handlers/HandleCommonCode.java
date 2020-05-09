package lombok.eclipse.handlers;

import static lombok.eclipse.Eclipse.fromQualifiedName;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.mangosdk.spi.ProviderFor;

import lombok.AccessLevel;
import lombok.InternationlCode;
import lombok.core.AST;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.handlers.HandleConstructor.SkipIfConstructorExists;

/**
 * 
 * @author haijun.lian
 *
 *         2020年5月9日
 */
@ProviderFor(EclipseAnnotationHandler.class) public class HandleCommonCode extends EclipseAnnotationHandler<InternationlCode> {
	private final String CODE = "code";
	private final String KEY = "key";
	private final String INTERNATIONL = "internationl";
	
	@Override public void handle(AnnotationValues<InternationlCode> annotation, Annotation ast, EclipseNode annotationNode) {
		InternationlCode commonCode = annotation.getInstance();
		String path = commonCode.path();
		EclipseNode typeNode = annotationNode.up();
		
		if (typeNode.getKind() != AST.Kind.TYPE) {
			annotationNode.addError("@InternationlCode is only supported on a class.");
			return;
		}
		
		TypeDeclaration typeDecl = null;
		if (typeNode.get() instanceof TypeDeclaration) {
			typeDecl = (TypeDeclaration) typeNode.get();
		}
		int modifiers = typeDecl == null ? 0 : typeDecl.modifiers;
		boolean notAClass = (modifiers & (ClassFileConstants.AccInterface | ClassFileConstants.AccAnnotation | ClassFileConstants.AccEnum)) != 0;
		
		if (typeDecl == null || notAClass) {
			annotationNode.addError("@InternationlCode is only supported on a class.");
			return;
		}
		// 插入字段/插入get方法
		insertField(CODE, ast, typeNode, annotationNode);
		insertField(KEY, ast, typeNode, annotationNode);
		
		// 插入私有构造器
		new HandleConstructor().generateConstructor(typeNode, AccessLevel.PRIVATE, Collections.<EclipseNode>emptyList(), false, "", SkipIfConstructorExists.NO, null, Collections.<Annotation>emptyList(), annotationNode);
		new HandleConstructor().generateConstructor(typeNode, AccessLevel.PRIVATE, getRequiredFields(typeNode), false, "", SkipIfConstructorExists.NO, null, Collections.<Annotation>emptyList(), annotationNode);
		
		// 插入properties中定义的public static final 属性
		String packageName = typeNode.getPackageDeclaration();
		String clazzName = typeNode.getName();
		clazzName = packageName == null || "".equals(packageName) ? clazzName : packageName + "." + clazzName;
		insertField(createFromProperties(path, clazzName, ast, annotationNode), ast, typeNode, annotationNode);
		
		annotationNode.rebuild();
	}
	
	private void insertField(List<FieldDeclaration> fields, Annotation ast, EclipseNode typeNode, EclipseNode annotationNode) {
		if (fields == null || fields.size() < 1) {
			return;
		}
		for (FieldDeclaration field : fields) {
			String fieldName = new String(field.name);
			if (fieldExists(fieldName, typeNode) != EclipseHandlerUtil.MemberExistsResult.NOT_EXISTS) {
				annotationNode.addWarning("Field '" + fieldName + "' already exists.");
				continue;
			}
			injectField(typeNode, field);
		}
	}
	
	private List<FieldDeclaration> createFromProperties(String path, String clazz, Annotation source, EclipseNode annotationNode) {
		List<FieldDeclaration> ss = new ArrayList<FieldDeclaration>(16);
		try {
			String location = getProjectPath();
			if (location == null || "".equals(location)) {
				return ss;
			}
			String af = annotationNode.getFileName();
			if (af == null || "".equals(af)) {
				return ss;
			}
			String absolute = annotationNode.getAst().getAbsoluteFileLocation().getPath();
			if (absolute == null || "".equals(absolute)) {
				return ss;
			}
			int index = af.indexOf("/", 1);
			String cf = af.substring(index);
			String projectPath = absolute.replace(cf, "");
			
			if (projectPath.equals(absolute)) {
				// 无法获取项目路径
				return ss;
			}
			File projectFile = new File(projectPath);
			File internationlFile = null;
			if (projectFile.exists() && projectFile.isDirectory()) {
				internationlFile = getFile(projectFile);
			}
			if (internationlFile == null) {
				return ss;
			}
			String fp = internationlFile.getAbsolutePath() + "/" + path;
			File target = new File(fp);
			if (!target.exists() || !target.isFile()) {
				return ss;
			}
			Properties ps = new Properties();
			ps.load(new FileInputStream(target));
			
			for (Entry<Object, Object> en : ps.entrySet()) {
				String key = (String) en.getKey();
				String val = (String) en.getValue();
				ss.add(createClassVariable(clazz, key, val, source));
			}
			
		} catch (Exception e) {
			annotationNode.addError("get properties has some error," + e.getMessage());
		}
		return ss;
	}
	
	private File getFile(File path) {
		File result = null;
		int count = 0;
		do {
			File[] files = path.listFiles();
			if (files != null) {
				for (File asb : files) {
					if (asb.getName().equals(INTERNATIONL) && asb.isDirectory()) {
						result = asb;
						break;
					}
				}
			}
			path = path.getParentFile();
			count++;
		} while (path != null && count < 3);
		
		return result;
	}
	
	private String getProjectPath() {
		return Platform.getLocation().toString();
	}
	
	private FieldDeclaration createClassVariable(String clazz, String fieldName, String values, Annotation source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		@SuppressWarnings("unused")
		long p = (long) pS << 32 | pE;
		// private static final clazz log = new clazz(code,key);
		FieldDeclaration fieldDecl = new FieldDeclaration(fieldName.toCharArray(), 0, -1);
		setGeneratedBy(fieldDecl, source);
		fieldDecl.declarationSourceEnd = -1;
		fieldDecl.modifiers = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;
		
		fieldDecl.type = createTypeReference(clazz, source);
		
		AllocationExpression expr = new AllocationExpression();
		setGeneratedBy(expr, source);
		expr.type = createTypeReference(clazz, source);
		String[] vs = values.split(",");
		String v1 = vs[0].trim();
		String v2 = "";
		if (vs.length > 1) {
			v2 = vs[1].trim();
		} else {
			v2 = vs[0].trim();
			v1 = "";
		}
		if ("".equals(v1)) {
			// 默认code码为 000000 成功
			v1 = "000000";
		}
		
		Expression code = new StringLiteral(v1.toCharArray(), pS, pE, 0);
		Expression key = new StringLiteral(v2.toCharArray(), pS, pE, 0);
		
		expr.arguments = new Expression[] {code, key};
		expr.sourceStart = pS;
		expr.sourceEnd = expr.statementEnd = pE;
		
		fieldDecl.initialization = expr;
		
		return fieldDecl;
	}
	
	private List<EclipseNode> getRequiredFields(EclipseNode typeNode) {
		List<EclipseNode> fields = new ArrayList<EclipseNode>();
		EclipseNode code = null;
		EclipseNode key = null;
		for (EclipseNode child : typeNode.down()) {
			if (child.getKind() != Kind.FIELD) {
				continue;
			}
			FieldDeclaration fieldDecl = (FieldDeclaration) child.get();
			String fieldName = new String(fieldDecl.name);
			if (CODE.equals(fieldName)) {
				code = child;
			} else if (KEY.equals(fieldName)) {
				key = child;
			}
			if (code != null && key != null) {
				break;
			}
		}
		fields.add(code);
		fields.add(key);
		return fields;
	}
	
	private void insertField(String fieldName, Annotation ast, EclipseNode typeNode, EclipseNode annotationNode) {
		if (fieldExists(fieldName, typeNode) != EclipseHandlerUtil.MemberExistsResult.NOT_EXISTS) {
			annotationNode.addWarning("Field '" + fieldName + "' already exists.");
			return;
		}
		
		FieldDeclaration field = createField(ast, fieldName);
		EclipseNode node = injectField(typeNode, field);
		new HandleGetter().generateGetterForField(node, ast, AccessLevel.PUBLIC, false);
		
	}
	
	private FieldDeclaration createField(Annotation source, String fieldName) {
		// private String code;
		FieldDeclaration fieldDecl = new FieldDeclaration(fieldName.toCharArray(), 0, -1);
		setGeneratedBy(fieldDecl, source);
		fieldDecl.declarationSourceEnd = -1;
		fieldDecl.modifiers = Modifier.PRIVATE;
		fieldDecl.type = createTypeReference("java.lang.String", source);
		
		return fieldDecl;
	}
	
	private TypeReference createTypeReference(String typeName, Annotation source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		
		TypeReference typeReference;
		String dot = ".";
		if (typeName.contains(dot)) {
			
			char[][] typeNameTokens = fromQualifiedName(typeName);
			long[] pos = new long[typeNameTokens.length];
			Arrays.fill(pos, p);
			
			typeReference = new QualifiedTypeReference(typeNameTokens, pos);
		} else {
			typeReference = new QualifiedTypeReference(new char[][] {typeName.toCharArray()}, new long[] {p});
		}
		
		setGeneratedBy(typeReference, source);
		return typeReference;
	}
}
