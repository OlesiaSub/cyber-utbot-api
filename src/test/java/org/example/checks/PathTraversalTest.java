package org.example.inter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

public final class PathTraversalTest {
    ///region Test suites for executable org.example.inter.PathTraversal.getDescription
    
    ///region SYMBOLIC EXECUTION: SUCCESSFUL EXECUTIONS for method getDescription()
    
    /**
    @utbot.classUnderTest {@link PathTraversal}
 * @utbot.methodUnderTest {@link org.example.inter.PathTraversal#getDescription()}
 * @utbot.returnsFrom {@code return "path traversal";}
 *  */
    @Test
    @DisplayName("getDescription: -> return \"path traversal\"")
    public void testGetDescription_ReturnPathtraversal() {
        PathTraversal pathTraversal = new PathTraversal();
        
        String actual = pathTraversal.getDescription();
        
        String expected = "path traversal";
        assertEquals(expected, actual);
    }
    ///endregion
    
    ///endregion
    
    ///region Test suites for executable org.example.inter.PathTraversal.doGet
    
    ///region SYMBOLIC EXECUTION: ERROR SUITE for method doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
    
    /**
    @utbot.classUnderTest {@link PathTraversal}
 * @utbot.methodUnderTest {@link org.example.inter.PathTraversal#doGet(javax.servlet.http.HttpServletRequest,javax.servlet.http.HttpServletResponse)}
 * @utbot.throwsException {@link java.lang.NullPointerException} in: String s = req.getParameter(FIELD_NAME);
 *  */
    @Test
    @DisplayName("doGet: s = req.getParameter(FIELD_NAME) : True -> ThrowNullPointerException")
    public void testDoGet_ThrowNullPointerException() throws IOException  {
        PathTraversal pathTraversal = new PathTraversal();
        
        /* This test fails because method [org.example.inter.PathTraversal.doGet] produces [java.lang.NullPointerException]
            org.example.inter.PathTraversal.doGet(PathTraversal.java:38) */
        pathTraversal.doGet(null, null);
    }
    
    /**
    @utbot.classUnderTest {@link PathTraversal}
 * @utbot.methodUnderTest {@link org.example.inter.PathTraversal#doGet(javax.servlet.http.HttpServletRequest,javax.servlet.http.HttpServletResponse)}
 * @utbot.throwsException {@link org.mockito.exceptions.base.MockitoException} in: RandomAccessFile raf = new RandomAccessFile(name, "rw");
 *  */
    @Test
    @DisplayName("doGet: raf = new RandomAccessFile(name, \"rw\") -> ThrowMockitoException")
    @org.cyber.utils.VulnerabilityInfo("arbitrary file modification")
    public void testDoGet_ThrowMockitoException() throws IOException  {
        PathTraversal pathTraversal = new PathTraversal();
        HttpServletRequest httpServletRequestMock = mock(HttpServletRequest.class);
        (org.mockito.Mockito.when(httpServletRequestMock.getParameter("name"))).thenReturn("../../../../etc/passwd");
        
        /* This test fails because method [org.example.inter.PathTraversal.doGet] produces [org.mockito.exceptions.base.MockitoException: \nCannot call abstract real method on java object!\nCalling real methods is only possible when mocking non abstract method.\n  //correct example:\n  when(mockOfConcreteClass.nonAbstractMethod()).thenCallRealMethod();]
            org.utbot.instrumentation.instrumentation.execution.constructors.MockValueConstructor$generateMockitoAnswer$1.answer(MockValueConstructor.kt:228)
            org.example.inter.PathTraversal.doGet(PathTraversal.java:38) */
        pathTraversal.doGet(httpServletRequestMock, null);
    }
    ///endregion
    
    ///endregion
    
    ///region Test suites for executable org.example.inter.PathTraversal.getVulnerabilityCount
    
    ///region SYMBOLIC EXECUTION: SUCCESSFUL EXECUTIONS for method getVulnerabilityCount()
    
    /**
    @utbot.classUnderTest {@link PathTraversal}
 * @utbot.methodUnderTest {@link org.example.inter.PathTraversal#getVulnerabilityCount()}
 * @utbot.returnsFrom {@code return 2;}
 *  */
    @Test
    @DisplayName("getVulnerabilityCount: -> return 2")
    public void testGetVulnerabilityCount_Return2() {
        PathTraversal pathTraversal = new PathTraversal();
        
        int actual = pathTraversal.getVulnerabilityCount();
        
        assertEquals(2, actual);
    }
    ///endregion
    
    ///endregion
}

