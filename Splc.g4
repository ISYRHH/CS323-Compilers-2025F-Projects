//lexer grammar Splc;
grammar Splc;

// Changes in Project 2: Splc.g4 contains both parser rules and lexer rules.
//  so there should be "grammar Splc;' instead of 'lexer grammer Splc;'

// IDEA Plugin Settings
// - Output Directory: src/main/java/
// - package name: generated.Splc

// =========================
// Parser Rules
// =========================

program
    : globalDef* EOF;

globalDef
    : specifier Identifier LPAREN funcArgs RPAREN LBRACE statement* RBRACE          // 函数定义
    | specifier varDec SEMI     // 全局变量定义
    | specifier SEMI  // 全局结构体声明
    ;

// 2.2 类型说明符 (specifier)
specifier
    : INT                          // 整型
    | CHAR                         // 字符型
    | STRUCT Identifier            // 结构体
    | STRUCT Identifier LBRACE (specifier varDec SEMI)* RBRACE  // 完整结构体
    ;

// 2.3 变量声明 (varDec)
varDec
    : Identifier                          // 简单变量声明
    | LPAREN varDec RPAREN                // 括号改变结合顺序
    | varDec LBRACK Number RBRACK         // 数组声明（[]优先级高于*）
    | STAR varDec                         // 指针声明
    ;

// 2.4 函数参数 (funcArgs)
funcArgs
    : (specifier varDec (COMMA specifier varDec)*)?  // 参数列表
    ;

// 2.5 语句 (statement)
statement
    : LBRACE statement* RBRACE                                    #BlockStmt  // 代码块
    | specifier varDec (ASSIGN expression)? SEMI                  #VarDecStmt  // 局部变量定义
    | IF LPAREN expression RPAREN statement (ELSE statement)?     #IfStmt  // If语句
    | WHILE LPAREN expression RPAREN statement                    #WhileStmt  // While语句
    | RETURN expression SEMI                                      #ReturnStmt  // 返回语句
    | expression SEMI                                             #ExpressionStmt  // 表达式语句
    ;

// 2.6 表达式 (expression)
expression
    : Identifier                                                                // 标识符
    | Number                                                                    // 数字常量
    | Char                                                                      // 字符常量
    | LPAREN expression RPAREN                                                  // 括号表达式
    | expression (INC | DEC)                                                    // 后缀自增/自减（后缀一元运算符）
    | Identifier LPAREN (expression (COMMA expression)*)? RPAREN                // 函数调用（后缀一元运算符）
    | expression LBRACK expression RBRACK                                       // 数组访问（后缀一元运算符）
    | expression DOT Identifier                                                 // 结构体访问（后缀一元运算符）
    | expression ARROW Identifier                                               // 结构体指针访问（后缀一元运算符）
    | <assoc=right> (INC | DEC | PLUS | MINUS | NOT | AMP | STAR) expression    // 前缀一元运算符
    | expression (STAR | DIV | MOD) expression                                  // 乘法/除法/取模（二元运算符，左结合）
    | expression (PLUS | MINUS) expression                                      // 加法/减法（二元运算符，左结合）
    | expression (LT | LE) expression                                           // 小于/小于等于（二元运算符，左结合）
    | expression (GT | GE) expression                                           // 大于/大于等于（二元运算符，左结合）
    | expression (EQ | NEQ) expression                                          // 等于/不等于（二元运算符，左结合）
    | expression AND expression                                                 // 逻辑与（二元运算符，左结合）
    | expression OR expression                                                  // 逻辑或（二元运算符，左结合）
    | <assoc=right> expression ASSIGN expression                                // 赋值（二元运算符，右结合）
    ;

// =========================
// Lexer Rules
// =========================

// ---------- Keywords ----------
INT     : 'int';
CHAR    : 'char';
STRUCT  : 'struct';
RETURN  : 'return';
IF      : 'if';
ELSE    : 'else';
WHILE   : 'while';

// ---------- Operators ----------
ASSIGN  : '=';
PLUS    : '+';
MINUS   : '-';
STAR    : '*';
DIV     : '/';
MOD     : '%';
LT      : '<';
LE      : '<=';
GT      : '>';
GE      : '>=';
EQ      : '==';
NEQ     : '!=';
AND     : '&&';
OR      : '||';
NOT     : '!';
INC     : '++';
DEC     : '--';
DOT     : '.';
ARROW   : '->';
AMP     : '&';

// ---------- Separators ----------
SEMI    : ';';
COMMA   : ',';
LPAREN  : '(';
RPAREN  : ')';
LBRACE  : '{';
RBRACE  : '}';
LBRACK  : '[';
RBRACK  : ']';

// ---------- Identifiers & Literals ----------
Identifier  : [a-zA-Z_][a-zA-Z_0-9]*;
Number      : [1-9][0-9]* | '0';
Char        : '\'\\\n\'' | '\'\\\t\'' | '\'\\\'\'' | '\'\\\\\'' | '\'\\0\'' | '\'' .*? '\'' ;

// ---------- Whitespace & Comments ----------
WS             : [ \t\r\n]+ -> skip;
LINE_COMMENT   : '//' ~[\r\n]* -> skip;
BLOCK_COMMENT  : '/*' .*? '*/' -> skip;