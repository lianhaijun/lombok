package lombok.javac.handlers;

import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.util.Map.Entry;
import java.util.Properties;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;

import lombok.AccessLevel;
import lombok.InternationlCode;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.handlers.HandleConstructor.SkipIfConstructorExists;

/**
 * 
 * @author haijun.lian
 *
 *         2020年5月9日
 */
@ProviderFor(JavacAnnotationHandler.class) public class HandleCommonCode extends JavacAnnotationHandler<InternationlCode> {
	private final String CODE = "code";
	private final String KEY = "key";
	private final String INTERNATIONL = "internationl";
	
	@Override public void handle(AnnotationValues<InternationlCode> annotation, JCAnnotation ast, JavacNode annotationNode) {
		InternationlCode commonCode = annotation.getInstance();
		String path = commonCode.path();
		JavacNode typeNode = annotationNode.up();
		boolean notAClass = !isClass(typeNode);
		
		if (notAClass) {
			annotationNode.addError("@InternationlCode is only supported on a class.");
			return;
		}
		// 插入字段/插入get方法
		insertField(CODE, ast, typeNode, annotationNode);
		insertField(KEY, ast, typeNode, annotationNode);
		
		// 插入私有构造器
		new HandleConstructor().generateConstructor(typeNode, AccessLevel.PRIVATE, List.<JCAnnotation>nil(), List.<JavacNode>nil(), false, "", SkipIfConstructorExists.NO, null, annotationNode);
		new HandleConstructor().generateConstructor(typeNode, AccessLevel.PRIVATE, List.<JCAnnotation>nil(), getRequiredFields(typeNode), false, "", SkipIfConstructorExists.NO, null, annotationNode);
		
		// 插入properties中定义的public static final 属性
		String clazzName = typeNode.getName();
		insertField(createFromProperties(path, clazzName, typeNode, annotationNode), typeNode, annotationNode);
		
		annotationNode.rebuild();
		
	}
	
	private void insertField(List<JCVariableDecl> fields, JavacNode typeNode, JavacNode annotationNode) {
		if (fields == null || fields.size() < 1) {
			return;
		}
		for (JCVariableDecl field : fields) {
			if (fieldExists(field.name.toString(), typeNode) != JavacHandlerUtil.MemberExistsResult.NOT_EXISTS) {
				annotationNode.addWarning("Field '" + field.name.toString() + "' already exists.");
				continue;
			}
			injectField(typeNode, field);
		}
	}
	
	private List<JCVariableDecl> createFromProperties(String path, String clazz, JavacNode typeNode, JavacNode annotationNode) {
		ListBuffer<JCVariableDecl> ss = new ListBuffer<JCVariableDecl>();
		try {
			URL url = getClass().getClassLoader().getResource("");
			if (url == null) {
				return ss.toList();
			}
			String filePath = url.getFile();
			File projectFile = new File(filePath);
			if (!projectFile.exists() || !projectFile.isDirectory()) {
				return ss.toList();
			}
			
			File internationlFile = getFile(projectFile);
			if (internationlFile == null) {
				return ss.toList();
			}
			String fp = internationlFile.getAbsolutePath() + "/" + path;
			File target = new File(fp);
			if (!target.exists() || !target.isFile()) {
				return ss.toList();
			}
			Properties ps = new Properties();
			ps.load(new FileInputStream(target));
			
			for (Entry<Object, Object> en : ps.entrySet()) {
				String key = (String) en.getKey();
				String val = (String) en.getValue();
				ss.append(createClassVariable(clazz, key, val, typeNode));
			}
			
		} catch (Exception e) {
			annotationNode.addError("get properties has some error," + e.getMessage());
		}
		return ss.toList();
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
		} while (path != null && count < 4);
		
		return result;
	}
	
	private JCVariableDecl createClassVariable(String className, String fileName, String values, JavacNode typeNode) {
		// 创建一个new语句 CombatJCTreeMain combatJCTreeMain = new CombatJCTreeMain();
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
		
		JavacTreeMaker maker = typeNode.getTreeMaker();
		ListBuffer<JCTree.JCExpression> ss = new ListBuffer<JCTree.JCExpression>();
		JCExpression code = maker.Literal(v1);
		JCExpression key = maker.Literal(v2);
		ss.add(code);
		ss.add(key);
		
		JCTree.JCNewClass ins = maker.NewClass(null, List.<JCExpression>nil(), maker.Ident(typeNode.toName(className)), ss.toList(), null);
		JCTree.JCVariableDecl classVariable = maker.VarDef(maker.Modifiers(Flags.PUBLIC | Flags.STATIC | Flags.FINAL), typeNode.toName(fileName), maker.Ident(typeNode.toName(className)), ins);
		return classVariable;
	}
	
	private List<JavacNode> getRequiredFields(JavacNode typeNode) {
		ListBuffer<JavacNode> fields = new ListBuffer<JavacNode>();
		JavacNode code = null;
		JavacNode key = null;
		for (JavacNode child : typeNode.down()) {
			if (child.getKind() != Kind.FIELD) {
				continue;
			}
			JCVariableDecl fieldDecl = (JCVariableDecl) child.get();
			// find fields code/key
			if (fieldDecl.name.toString().equals(CODE)) {
				code = child;
			} else if (fieldDecl.name.toString().equals(KEY)) {
				key = child;
			}
			
			if (code != null && key != null) {
				break;
			}
		}
		
		fields.append(code);
		fields.append(key);
		
		return fields.toList();
	}
	
	private void insertField(String fieldName, JCAnnotation ast, JavacNode typeNode, JavacNode annotationNode) {
		// 先检查字段是否存在 存在则不添加|不存在则添加
		if (fieldExists(fieldName, typeNode) != JavacHandlerUtil.MemberExistsResult.NOT_EXISTS) {
			annotationNode.addWarning("Field '" + fieldName + "' already exists.");
			return;
		}
		
		JavacTreeMaker maker = typeNode.getTreeMaker();
		JCTree.JCExpression fieldType = chainDotsString(typeNode, "java.lang.String");
		JCTree.JCVariableDecl fieldDecl = maker.VarDef(maker.Modifiers(Flags.PRIVATE), typeNode.toName(fieldName), fieldType, null);
		
		JavacNode fieldNode = injectFieldAndMarkGenerated(typeNode, fieldDecl);
		
		// 插入 getter 方法
		new HandleGetter().generateGetterForField(fieldNode, null, AccessLevel.PUBLIC, false);
		
	}
	
}
