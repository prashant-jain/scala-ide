package org.scalaide.core.testsetup


import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IncrementalProjectBuilder
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.Path
import org.eclipse.jdt.core.ICompilationUnit
import org.eclipse.jdt.core.IPackageFragmentRoot
import org.eclipse.jdt.core.IProblemRequestor
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.WorkingCopyOwner
import org.eclipse.text.edits.ReplaceEdit
import org.junit.Assert.assertNotNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.when
import org.scalaide.core.compiler.InteractiveCompilationUnit
import org.scalaide.core.internal.jdt.model.ScalaCompilationUnit
import org.scalaide.core.internal.jdt.model.ScalaSourceFile
import org.scalaide.core.IScalaProject
import org.scalaide.core.internal.project.ScalaProject

/** Base class for setting up tests that depend on a project found in the test-workspace.
 *
 *  Subclass this class with an `object'. The initialization will copy the given project
 *  from test-workspace to the target workspace, and retrieve the 'src/' package root in
 *  `srcPackageRoot'.
 *
 *  Reference the object form your test, so that the constructor is called and the project
 *  setup.
 *
 *  Example: `object HyperlinkDetectorTests extends TestProjectSetup("hyperlinks")'
 *
 */
class TestProjectSetup(projectName: String, srcRoot: String = "/%s/src/", val bundleName: String = "org.scala-ide.sdt.core.tests") extends ProjectBuilder {
  private[core] lazy val internalProject: ScalaProject = BlockingProgressMonitor.waitUntilDone {
    SDTTestUtils.internalSetupProject(projectName, bundleName)(_)
  }

  /** The ScalaProject corresponding to projectName, after copying to the test workspace. */
  override lazy val project: IScalaProject = internalProject

  /** The package root corresponding to /src inside the project. */
  lazy val srcPackageRoot: IPackageFragmentRoot = {
    val javaProject = JavaCore.create(project.underlying)

    javaProject.open(null)
    javaProject.findPackageFragmentRoot(new Path(srcRoot.format(projectName)))
  }

  assertNotNull(srcPackageRoot)

  srcPackageRoot.open(null)

  def file(path: String): IFile = {
    project.underlying.getFile(path)
  }

  /** Return the compilation unit corresponding to the given path, relative to the src folder.
   *  for example: "scala/collection/Map.scala"
   */
  def compilationUnit(path: String): ICompilationUnit = {
    val segments = path.split("/")
    srcPackageRoot.getPackageFragment(segments.init.mkString(".")).getCompilationUnit(segments.last)
  }

  /** Return a sequence of compilation units corresponding to the given paths. */
  def compilationUnits(paths: String*): Seq[ICompilationUnit] =
    paths.map(compilationUnit)

  /** Return a sequence of Scala compilation units corresponding to the given paths. */
  def scalaCompilationUnits(paths: String*): Seq[ScalaSourceFile] =
    paths.map(scalaCompilationUnit)

  /** Return the Scala compilation unit corresponding to the given path, relative to the src folder.
   *  for example: "scala/collection/Map.scala".
   */
  def scalaCompilationUnit(path: String): ScalaSourceFile =
    compilationUnit(path).asInstanceOf[ScalaSourceFile]

  def createSourceFile(packageName: String, unitName: String)(contents: String): ScalaSourceFile = {
    val pack = SDTTestUtils.createSourcePackage(packageName)(project)
    SDTTestUtils.createCompilationUnit(pack, unitName, contents).asInstanceOf[ScalaSourceFile]
  }

  def reload(unit: InteractiveCompilationUnit): Unit = {
    // first, 'open' the file by telling the compiler to load it
    unit.withSourceFile { (src, compiler) =>
      compiler.askReload(List(unit)).get
    }
  }

  def parseAndEnter(unit: InteractiveCompilationUnit): Unit = {
    unit.withSourceFile { (src, compiler) =>
      val dummy = new compiler.Response[compiler.Tree]
      compiler.askParsedEntered(src, false, dummy)
      dummy.get
    }
  }

  def findMarker(marker: String) = SDTTestUtils.findMarker(marker)

  /** Emulate the opening of a scala source file (i.e., it tries to
   * reproduce the steps performed by JDT when opening a file in an editor).
   *
   * @param srcPath the path to the scala source file
   * */
  def open(srcPath: String): ScalaSourceFile = {
    val unit = scalaCompilationUnit(srcPath)
    openWorkingCopyFor(unit)
    reload(unit)
    unit
  }

  /** Open a working copy of the passed `unit` */
  private def openWorkingCopyFor(unit: ScalaSourceFile): Unit = {
    val requestor = mock(classOf[IProblemRequestor])
    // the requestor must be active, or unit.getWorkingCopy won't trigger the Scala
    // structure builder
    when(requestor.isActive()).thenReturn(true)

    val owner = new WorkingCopyOwner() {
      override def getProblemRequestor(unit: org.eclipse.jdt.core.ICompilationUnit): IProblemRequestor = requestor
    }

    // this will trigger the Scala structure builder
    unit.getWorkingCopy(owner, new NullProgressMonitor)
  }

  /** Wait until the passed `unit` is entirely typechecked. */
  def waitUntilTypechecked(unit: ScalaCompilationUnit): Unit = {
    // give a chance to the background compiler to report the error
    unit.withSourceFile { (source, compiler) =>
      compiler.askLoadedTyped(source, true).get // wait until unit is typechecked
    }
  }

  /** Open the passed `source` and wait until it has been fully typechecked.*/
  def openAndWaitUntilTypechecked(source: ScalaSourceFile): Unit = {
    val sourcePath = source.getPath()
    val projectSrcPath = project.underlying.getFullPath() append "src"
    val path = sourcePath.makeRelativeTo(projectSrcPath)
    open(path.toOSString())
    waitUntilTypechecked(source)
  }

  /**
   * Allows to modify sources in test workspace.
   *
   * @param compilationUnitPath path to file which we'll change
   * @param lineNumber line which will be removed (line numbers start from 1)
   * @param newLine code inserted in place of line with lineNumber
   */
  def modifyLine(compilationUnitPath: String, lineNumber: Int, newLine: String): Unit = {
    val lineIndex = lineNumber - 1
    val cu = compilationUnit(compilationUnitPath)
    val code = cu.getSource
    val lines = code.split('\n').toList
    val newLines = lines.updated(lineIndex, newLine)
    val newCode = newLines.mkString("\n")

    val textEdit = new ReplaceEdit(0, code.length(), newCode)
    cu.applyTextEdit(textEdit, new NullProgressMonitor)
    cu.save(new NullProgressMonitor, true)
  }

  /**
   * Be aware that it can be heavy. Moreover, it's needed to ensure that
   * events are already properly propagated after the build.
   */
  def buildIncrementally(): Unit =
    project.underlying.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, new NullProgressMonitor)
}
